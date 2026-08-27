package dev.worktrace.tiktrace.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewCompat
import dev.worktrace.tiktrace.App
import dev.worktrace.tiktrace.BuildConfig
import dev.worktrace.tiktrace.R
import dev.worktrace.tiktrace.capture.HookInstaller
import dev.worktrace.tiktrace.data.CaptureStats
import dev.worktrace.tiktrace.databinding.ActivityFeedBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TikTok 모바일 웹을 싣고 그 응답을 캡처하는 유일한 화면.
 *
 * Phase 1 의 범위는 "원본을 빠짐없이 쌓는 것"까지다. 정규화·점수화는 Phase 2 이후로
 * 미루되, 캡처가 실제로 되고 있는지는 눈으로 확인할 수 있어야 하므로 하단에 현황
 * 표시줄과 진단·내보내기 메뉴를 둔다.
 */
class FeedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeedBinding
    private lateinit var installation: HookInstaller.Result

    private val repository by lazy { (application as App).repository }
    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.KOREA)

    private var latest: CaptureStats? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME)
    ) { uri -> uri?.let(::export) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        configure(binding.webView)

        // loadUrl 보다 먼저여야 한다. 순서가 뒤집히면 첫 화면 분량을 통째로 놓친다.
        installation = HookInstaller.install(binding.webView, repository)

        binding.statusBar.setOnClickListener(::showMenu)

        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeStats().collect { render(it) }
            }
        }

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            binding.webView.loadUrl(START_URL)
        }

        if (!installation.fullySupported) {
            Toast.makeText(this, R.string.warn_fallback, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // 기본 WebView UA 에는 "; wv" 표식이 붙어 일부 경로에서 차별 취급된다.
            // 크롬 버전 문자열은 그대로 두고 그 표식만 지운다 — 하드코딩하면 금방 낡는다.
            userAgentString = WebSettings.getDefaultUserAgent(webView.context)
                .replace("; wv)", ")")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
            object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "console: ${message.message()} @${message.lineNumber()}")
                    return true
                }
            }
        } else {
            WebChromeClient()
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun render(stats: CaptureStats) {
        latest = stats
        binding.statusBar.text = if (stats.payloads == 0) {
            getString(R.string.status_waiting)
        } else {
            getString(
                R.string.status_format,
                stats.payloads,
                stats.items,
                formatSize(stats.bytes),
                clock.format(Date(stats.lastAt)),
            )
        }
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, MENU_EXPORT, 0, R.string.action_export)
            menu.add(Menu.NONE, MENU_DIAGNOSTICS, 1, R.string.action_diagnostics)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EXPORT -> { startExport(); true }
                    MENU_DIAGNOSTICS -> { showDiagnostics(); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun startExport() {
        if ((latest?.payloads ?: 0) == 0) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        exportLauncher.launch("tiktrace-${fileStamp.format(Date())}.jsonl")
    }

    private fun export(uri: Uri) {
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { repository.exportJsonl(it) }
                        ?: error("출력 스트림을 열 수 없습니다")
                }
            }
            val message = outcome.fold(
                onSuccess = { getString(R.string.export_done, it) },
                onFailure = { getString(R.string.export_failed, it.message.orEmpty()) },
            )
            Toast.makeText(this@FeedActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** 어느 경로로 후킹이 붙었는지 보여준다 — "왜 초반이 비었나"에 답하려면 필요하다. */
    private fun showDiagnostics() {
        val webViewVersion = WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "?"
        val message = buildString {
            appendLine(getString(R.string.diag_document_start, mark(installation.documentStart)))
            appendLine(getString(R.string.diag_bridge, mark(installation.modernBridge)))
            appendLine(getString(R.string.diag_webview, webViewVersion))
            appendLine()
            append(
                getString(
                    R.string.diag_counters,
                    repository.duplicateCount(),
                    repository.malformedCount(),
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_diagnostics)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun mark(supported: Boolean): String =
        getString(if (supported) R.string.diag_ok else R.string.diag_fallback)

    private fun formatSize(chars: Long): String = when {
        chars >= 1_000_000 -> "%.1fMB".format(chars / 1_000_000.0)
        chars >= 1_000 -> "%dKB".format(chars / 1_000)
        else -> "${chars}B"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // 세션 쿠키를 디스크로 내린다. 다음 실행에서 로그인이 유지되는 이유.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        binding.root.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FeedActivity"
        const val START_URL = "https://www.tiktok.com/foryou"
        const val EXPORT_MIME = "application/x-ndjson"
        const val MENU_EXPORT = 1
        const val MENU_DIAGNOSTICS = 2
    }
}
