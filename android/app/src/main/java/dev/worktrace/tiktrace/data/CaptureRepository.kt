package dev.worktrace.tiktrace.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 브리지에서 들어온 원본 문자열을 파싱 없이 적재한다.
 *
 * 봉투 형식은 hook.js 와 맞물려 있다:  url <SEP> timestamp <SEP> body
 * SEP 는 U+001F. JSON 은 제어문자를 반드시 이스케이프하므로 raw U+001F 는
 * 유효한 본문 안에 절대 등장할 수 없다 — 구분자로 안전한 이유다.
 */
class CaptureRepository(
    private val dao: RawPayloadDao,
    private val scope: CoroutineScope,
) {

    /** 브리지와 DB 사이를 직렬화한다. 소비자는 하나. */
    private val inbox = Channel<String>(capacity = QUEUE_CAPACITY)

    private val duplicates = AtomicInteger()
    private val malformed = AtomicInteger()

    init {
        scope.launch(Dispatchers.IO) {
            for (raw in inbox) {
                runCatching { persist(raw) }
                    .onFailure { Log.w(TAG, "적재 실패", it) }
            }
        }
    }

    /**
     * WebView UI 스레드 또는 JavaBridge 스레드에서 호출된다. 절대 블로킹하지 않는다.
     *
     * 버퍼가 차면 send 가 코루틴 안에서 대기하므로, 데이터를 버리지 않으면서도
     * 호출자 스레드는 막히지 않는다. 캡처는 소급 복구가 불가능하니 드롭보다
     * 백프레셔가 옳다.
     */
    fun submit(raw: String) {
        scope.launch { inbox.send(raw) }
    }

    fun observeStats(): Flow<CaptureStats> = dao.observeStats().distinctUntilChanged()

    fun duplicateCount(): Int = duplicates.get()

    fun malformedCount(): Int = malformed.get()

    private suspend fun persist(raw: String) {
        val firstSep = raw.indexOf(SEP)
        val secondSep = if (firstSep < 0) -1 else raw.indexOf(SEP, firstSep + 1)
        if (secondSep < 0) {
            malformed.incrementAndGet()
            Log.w(TAG, "봉투 형식이 아닌 메시지 (${raw.length}자)")
            return
        }

        val url = raw.substring(0, firstSep)
        val capturedAt = raw.substring(firstSep + 1, secondSep).toLongOrNull()
            ?: System.currentTimeMillis()
        val body = raw.substring(secondSep + 1)
        if (body.isBlank()) return

        // 파싱은 건수 집계와 유효성 판정에만 쓴다. 저장되는 것은 언제나 원본이다.
        val parsed = runCatching { JSONObject(body) }.getOrNull()

        val rowId = dao.insertIgnoringDuplicates(
            RawPayload(
                capturedAt = capturedAt,
                url = url,
                surface = Surface.of(url),
                body = body,
                digest = sha256(body),
                itemCount = parsed?.let(::countItems) ?: 0,
                byteSize = body.length,
                isJson = parsed != null,
            )
        )
        if (rowId == INSERT_IGNORED) duplicates.incrementAndGet()
    }

    /**
     * JSONL 로 내보낸다. 한 줄에 응답 하나.
     * 유효한 JSON 본문은 그대로 삽입해 원본 바이트를 보존한다.
     */
    suspend fun exportJsonl(out: OutputStream): Int = withContext(Dispatchers.IO) {
        var written = 0
        out.bufferedWriter().use { writer ->
            var afterId = 0L
            while (true) {
                val page = dao.pageAfter(afterId, PAGE_SIZE)
                if (page.isEmpty()) break
                for (row in page) {
                    writer.append("{\"captured_at\":").append(row.capturedAt.toString())
                    writer.append(",\"url\":").append(JSONObject.quote(row.url))
                    writer.append(",\"surface\":").append(JSONObject.quote(row.surface))
                    writer.append(",\"item_count\":").append(row.itemCount.toString())
                    writer.append(",\"body\":")
                    writer.append(if (row.isJson) row.body else JSONObject.quote(row.body))
                    writer.append("}\n")
                    written++
                }
                afterId = page.last().id
            }
        }
        written
    }

    /** 응답 하나에 실려 온 영상 수. 형태별 분기는 설계 문서 §3.2 와 같다. */
    private fun countItems(json: JSONObject): Int {
        json.optJSONArray("itemList")?.let { return it.length() }
        json.optJSONObject("itemInfo")?.optJSONObject("itemStruct")?.let { return 1 }
        json.optJSONArray("data")?.let { return it.length() }
        return 0
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private companion object {
        const val TAG = "CaptureRepository"

        /** hook.js 의 String.fromCharCode(31) 과 반드시 일치해야 한다. */
        val SEP = Char(31)

        const val QUEUE_CAPACITY = 64
        /** 한 행이 수백 KB 다. 페이지를 키우면 내보내기에서 바로 OOM 이 난다. */
        const val PAGE_SIZE = 10
        const val INSERT_IGNORED = -1L
        const val HEX = "0123456789abcdef"
    }
}
