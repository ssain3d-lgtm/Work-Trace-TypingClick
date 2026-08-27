# addJavascriptInterface 폴백 경로에서 쓰이는 브리지는 난독화되면 안 된다.
-keepclassmembers class dev.worktrace.tiktrace.capture.LegacyBridge {
    @android.webkit.JavascriptInterface <methods>;
}
