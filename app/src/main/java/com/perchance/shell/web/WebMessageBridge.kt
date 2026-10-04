package com.perchance.shell.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.perchance.shell.download.DownloadManager
import org.json.JSONObject

/**
 * Secure bridge using WebMessagePort / addWebMessageListener.
 * Only accepts messages from allowed origins.
 */
class WebMessageBridge(
    private val webView: WebView,
    private val downloadManager: DownloadManager
) {

    fun attach() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                "shell",
                setOf("https://perchance.org", "https://www.perchance.org")
            ) { _, message, _, _, _ ->
                try {
                    val data = message.data ?: return@addWebMessageListener
                    val json = JSONObject(data)
                    val id = json.optString("id")
                    val mime = json.optString("mime")
                    val base64 = json.optString("base64")
                    if (id.isNotEmpty() && base64.isNotEmpty()) {
                        downloadManager.onImageReceived(id, mime, base64)
                    }
                } catch (_: Exception) {
                    // ignore malformed
                }
            }
        }
    }

    /** Ask all frames to flush their image registry */
    fun requestFlush() {
        val js = """
            (function() {
                try {
                    if (window.__perchanceShellFlush) {
                        window.__perchanceShellFlush();
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    companion object {
        // Minimal document-start capture script (MutationObserver based)
        // In a full implementation this is injected via WebViewCompat.addDocumentStartJavaScript
        val CAPTURE_SCRIPT = """
            (function() {
                if (window.__perchanceShellInstalled) return;
                window.__perchanceShellInstalled = true;
                const registry = new Map();
                function hash(str) {
                    let h = 0;
                    for (let i = 0; i < str.length; i++) h = ((h << 5) - h) + str.charCodeAt(i) | 0;
                    return h.toString(16);
                }
                function collect(img) {
                    if (!img || !img.src || img.naturalWidth === 0) return;
                    const key = hash(img.src.substring(0, 200));
                    if (registry.has(key)) return;
                    registry.set(key, img);
                }
                const obs = new MutationObserver(() => {
                    document.querySelectorAll('img').forEach(collect);
                });
                obs.observe(document.documentElement, { childList: true, subtree: true });
                window.__perchanceShellFlush = function() {
                    registry.forEach((img, key) => {
                        try {
                            // For real capture we would convert to base64 / postMessage
                            // Placeholder: just log count
                        } catch(e) {}
                    });
                };
            })();
        """.trimIndent()
    }
}
