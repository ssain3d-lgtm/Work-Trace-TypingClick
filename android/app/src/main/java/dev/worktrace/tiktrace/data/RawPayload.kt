package dev.worktrace.tiktrace.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 캡처한 응답 하나. 원본 본문을 손대지 않고 그대로 보관한다.
 *
 * TikTok 은 내부 필드를 예고 없이 바꾼다. 원본이 남아 있으면 파서가 깨져도
 * 나중에 재파싱으로 복구되지만, 정규화 결과만 저장하면 그 기간 데이터는
 * 영구히 잃는다. Phase 1 이 "raw 우선"인 이유가 이것이다.
 */
@Entity(
    tableName = "raw_payload",
    indices = [
        Index(value = ["digest"], unique = true),
        Index(value = ["captured_at"]),
    ],
)
data class RawPayload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 페이지에서 찍은 시각(epoch millis). 기기 시계 기준. */
    @ColumnInfo(name = "captured_at") val capturedAt: Long,

    val url: String,

    /** 노출 맥락. 나중에 코호트를 나눌 때 쓴다. */
    val surface: String,

    /** 응답 본문 원본. 절대 가공하지 않는다. */
    val body: String,

    /** 본문 SHA-256. 같은 응답이 두 경로로 들어오는 것을 막는다. */
    val digest: String,

    @ColumnInfo(name = "item_count") val itemCount: Int,

    /** 본문 길이(문자 수). 저장량 파악용 근사치. */
    @ColumnInfo(name = "byte_size") val byteSize: Int,

    /** 파싱 가능한 JSON인지. 내보내기에서 그대로 삽입할지 결정한다. */
    @ColumnInfo(name = "is_json") val isJson: Boolean,
)

/** 어느 화면에서 본 응답인가. */
object Surface {
    const val FYP = "fyp"
    const val PROFILE = "profile"
    const val DETAIL = "detail"
    const val SEARCH = "search"
    const val UNKNOWN = "unknown"

    fun of(url: String): String = when {
        url.contains("/api/recommend/item_list") -> FYP
        url.contains("/api/post/item_list") -> PROFILE
        url.contains("/api/item/detail") -> DETAIL
        url.contains("/api/search/") -> SEARCH
        else -> UNKNOWN
    }
}

/** 상태 표시줄에 뿌리는 집계값. */
data class CaptureStats(
    val payloads: Int,
    val items: Int,
    val lastAt: Long,
    val bytes: Long,
)
