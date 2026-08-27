package dev.worktrace.tiktrace.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.worktrace.tiktrace.data.CaptureRepository

/**
 * 데스크톱 확장의 `world:"MAIN"` + `run_at:"document_start"` 에 대응하는 설치기.
 *
 * - [WebViewFeature.DOCUMENT_START_SCRIPT] — 페이지의 어떤 스크립트보다 먼저 후킹을 넣는다.
 * - [WebViewFeature.WEB_MESSAGE_LISTENER]  — JS 에서 네이티브로 오는 채널.
 *   `addJavascriptInterface` 와 달리 허용 원본을 지정할 수 있어 안전하다.
 *
 * 둘 다 Android System WebView 106+ 를 요구한다. 미지원 기기에서는 각각 폴백하며,
 * 어느 경로로 붙었는지는 [Result] 로 돌려준다 — 진단 화면에서 확인할 수 있어야
 * "왜 초반 데이터가 비었는가" 같은 질문에 답할 수 있다.
 */
object HookInstaller {

    private const val BRIDGE = "ttBridge"
    private const val HOOK_ASSET = "hook.js"

    private val ALLOWED_ORIGINS = setOf(
        "https://*.tiktok.com",
        "https://tiktok.com",
    )

    data class Result(val modernBridge: Boolean, val documentStart: Boolean) {
        val fullySupported: Boolean get() = modernBridge && documentStart
    }

    /** [WebView.loadUrl] 보다 반드시 먼저 호출해야 한다. */
    @SuppressLint("JavascriptInterface")
    fun install(webView: WebView, repository: CaptureRepository): Result {
        val script = webView.context.assets.open(HOOK_ASSET)
            .use { it.readBytes().decodeToString() }

        val modernBridge = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (modernBridge) {
            // onPostMessage 는 UI 스레드에서 불린다 — submit 은 즉시 반환해야 한다.
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE,
                ALLOWED_ORIGINS,
            ) { _, message, _, _, _ ->
                message.data?.let(repository::submit)
            }
        } else {
            webView.addJavascriptInterface(LegacyBridge(repository), BRIDGE)
        }

        val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStart) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, ALLOWED_ORIGINS)
        }

        // WebViewClient 는 항상 설정한다. 없으면 TikTok 모바일 웹의 딥링크가
        // 네이티브 앱을 열면서 캡처가 끊긴다.
        webView.webViewClient = CaptureWebViewClient(
            fallbackScript = script.takeUnless { documentStart },
        )

        return Result(modernBridge, documentStart)
    }
}

/**
 * WEB_MESSAGE_LISTENER 미지원 기기용 폴백.
 * JS 쪽 호출 형태(`ttBridge.postMessage`)가 동일하므로 hook.js 는 그대로 쓴다.
 *
 * public 으로 둔다 — internal 이면 Kotlin 이름 맹글링이 @JavascriptInterface 노출을
 * 깨뜨릴 여지가 있고, 이 경로는 실패해도 조용해서 알아채기 어렵다.
 *
 * 주의: addJavascriptInterface 는 이 WebView 의 모든 프레임에 노출된다.
 * tiktok.com 만 싣는 단일 목적 앱이라 감수하지만, 다른 URL 을 열게 되면 재검토해야 한다.
 */
class LegacyBridge(private val repository: CaptureRepository) {

    /** JavaBridge 스레드에서 호출된다. */
    @JavascriptInterface
    fun postMessage(payload: String) {
        repository.submit(payload)
    }
}

internal class CaptureWebViewClient(private val fallbackScript: String?) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // 폴백 경로. document-start 보다 늦게 돌므로 첫 화면 분량을 놓칠 수 있다.
        fallbackScript?.let { view.evaluateJavascript(it, null) }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme?.lowercase()
        // snssdk://, intent:// 등 네이티브 앱을 여는 딥링크를 삼킨다.
        return scheme != "http" && scheme != "https"
    }
}
