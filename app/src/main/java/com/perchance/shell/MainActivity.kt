package com.perchance.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var handleZone: FrameLayout
    private lateinit var sheet: LinearLayout
    private lateinit var offline: LinearLayout
    private lateinit var progressLine: View
    private lateinit var store: ImageStore
    private lateinit var capture: CaptureController

    private val ui = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hideSheet() }
    private var loadFailed = false
    private var bridgeReady = false
    private var devUnlocked = false
    private val domParts = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        store = ImageStore(this)
        Thread { store.cleanTmp() }.start()
        capture = CaptureController(store, ::render)

        buildUi()
        setupWebView()
        setupBridge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    capture.isActive -> { capture.cancel(); hideSheet() } // in-flight write finishes atomically
                    sheet.visibility == View.VISIBLE -> hideSheet()
                    offline.visibility == View.VISIBLE -> finish()
                    webView.canGoBack() && isAllowedUrl(webView.url) -> webView.goBack()
                    else -> finish()
                }
            }
        })

        window.decorView.post { hideBars() }
        // No session saving: every launch starts from a clean web profile.
        wipeWebData { webView.loadUrl(START_URL) }
    }

    // ---------- UI ----------

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(color(R.color.page_bg)) }
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        // Tiny handle at the bottom edge. The only visible native element.
        // Slim tab on the right edge, a little below centre: nothing on Perchance lives there.
        handleZone = FrameLayout(this).apply {
            setOnClickListener { showBar() }
            if (DEV_TOOLS) setOnLongClickListener {
                devUnlocked = true
                Toast.makeText(context, "Dev tools on", Toast.LENGTH_SHORT).show()
                showBar(); true
            }
            addView(View(context).apply {
                background = rounded(0x55FFFFFF, 2)
            }, FrameLayout.LayoutParams(dp(4), dp(44), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(4) })
        }
        root.addView(handleZone, FrameLayout.LayoutParams(dp(28), dp(96), Gravity.END or Gravity.CENTER_VERTICAL))
        root.post { handleZone.translationY = root.height * 0.05f }

        progressLine = View(this).apply {
            setBackgroundColor(color(R.color.accent))
            pivotX = 0f; scaleX = 0f; visibility = View.GONE
        }
        root.addView(progressLine, FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP))

        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            background = rounded(0xF2171717.toInt(), 26)
            elevation = dp(12).toFloat()
            visibility = View.GONE
            isClickable = true
        }
        root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        })

        offline = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            setBackgroundColor(color(R.color.page_bg))
            visibility = View.GONE
            isClickable = true
            addView(label("Unable to load Perchance", 20f, R.color.text, true))
            addView(label("Check your connection and try again.", 14f, R.color.text_muted, false).apply {
                setPadding(0, dp(8), 0, dp(24))
            })
            addView(pill("Retry", true) {
                loadFailed = false
                offline.visibility = View.GONE
                webView.loadUrl(START_URL)
            }, LinearLayout.LayoutParams(dp(200), -2))
        }
        root.addView(offline, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun label(t: String, sp: Float, c: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color(c)); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(t: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(color(if (primary) R.color.bg else R.color.text))
        background = rounded(color(if (primary) R.color.accent else R.color.border), 16)
        minHeight = dp(56)
        setOnClickListener { onClick() }
    }

    private fun downloadButton(onClick: () -> Unit) = TextView(this).apply {
        text = "\u2193  Download all"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(color(R.color.bg))
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF8AB8D8.toInt(), 0xFFB4D4EA.toInt()))
            .apply { cornerRadius = dp(20).toFloat() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }

    private fun resetSheet() {
        ui.removeCallbacks(autoHide)
        sheet.removeAllViews()
    }

    private fun reveal(autoHideMs: Long = 0) {
        handleZone.visibility = View.INVISIBLE
        if (sheet.visibility != View.VISIBLE) {
            sheet.visibility = View.VISIBLE
            sheet.alpha = 0f
            sheet.translationY = dp(60).toFloat()
            sheet.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        if (autoHideMs > 0) ui.postDelayed(autoHide, autoHideMs)
    }

    private fun hideSheet() {
        ui.removeCallbacks(autoHide)
        if (sheet.visibility != View.VISIBLE) return
        sheet.animate().alpha(0f).translationY(dp(60).toFloat()).setDuration(180).withEndAction {
            sheet.visibility = View.GONE
            handleZone.visibility = View.VISIBLE
        }.start()
    }

    private fun showBar() {
        if (capture.isActive) return
        resetSheet()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(downloadButton { startDownloadAll() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        val n = store.list().size
        row.addView(pill("\u25A6  Gallery" + if (n > 0) "  $n" else "", false) { openLibrary() }.apply {
            textSize = 15f; minHeight = dp(60); setPadding(dp(18), 0, dp(18), 0)
        }, LinearLayout.LayoutParams(-2, dp(60)).apply { leftMargin = dp(10) })
        sheet.addView(row)
        if (DEV_TOOLS && devUnlocked) {
            sheet.addView(pill("Copy DOM outline (dev)", false) { startDomDump() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        reveal(if (devUnlocked) 15000 else 5000)
    }

    // ---------- dev: DOM outline ----------

    private fun startDomDump() {
        domParts.clear()
        webView.evaluateJavascript("window.__pcsDump && window.__pcsDump()", null)
        Toast.makeText(this, "Collecting\u2026", Toast.LENGTH_SHORT).show()
        ui.postDelayed({
            val text = domParts.joinToString("\n\n")
            if (text.isEmpty()) {
                Toast.makeText(this, "No frames responded", Toast.LENGTH_LONG).show()
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dom", text))
                Toast.makeText(this, "Copied ${domParts.size} frame(s), ${text.length / 1024} KB", Toast.LENGTH_LONG).show()
            }
        }, 3000)
    }

    private fun onDomPart(raw: String) {
        try {
            val j = JSONObject(raw)
            domParts.add("FRAME: " + j.optString("url") + "\n" + j.optString("text"))
        } catch (_: Exception) {}
    }

    private fun showProgress(done: Int, total: Int, failed: Int, scanning: Boolean) {
        resetSheet()
        sheet.addView(label(if (scanning) "Looking for images" else "Downloading images", 17f, R.color.text, true).apply { gravity = Gravity.START })
        sheet.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = scanning
            max = maxOf(total, 1); progress = done
            progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
        }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(14); bottomMargin = dp(8) })
        if (!scanning) {
            val extra = if (failed > 0) "  \u00B7  $failed failed" else ""
            sheet.addView(label("$done of $total$extra", 13f, R.color.text_muted, false).apply { gravity = Gravity.START })
        }
        reveal()
    }

    private fun showFinished(f: CaptureController.State.Finished) {
        resetSheet()
        val ok = f.failed == 0 && f.saved + f.duplicates > 0
        when {
            f.total == 0 && f.saved + f.duplicates + f.failed == 0 -> {
                sheet.addView(label("No generated images found", 17f, R.color.text, true))
                sheet.addView(label("Generate an image first, then try again.", 13f, R.color.text_muted, false).apply { setPadding(0, dp(6), 0, dp(14)) })
                sheet.addView(pill("OK", false) { hideSheet() })
            }
            ok -> {
                val msg = if (f.saved > 0) "\u2713 ${f.saved} image${if (f.saved == 1) "" else "s"} saved privately" else "\u2713 Already saved"
                sheet.addView(label(msg, 17f, R.color.ok, true))
                if (f.duplicates > 0 && f.saved > 0)
                    sheet.addView(label("${f.duplicates} already saved", 13f, R.color.text_muted, false))
                if (f.files.isNotEmpty()) sheet.addView(thumbRow(f.files), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
                sheet.addView(pill("Open library", true) { openLibrary() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            }
            else -> {
                sheet.addView(label("${f.failed} image${if (f.failed == 1) "" else "s"} couldn\u2019t be saved", 17f, R.color.danger, true))
                sheet.addView(label(
                    (if (f.lowSpace) "Not enough storage. " else "") + "${f.saved + f.duplicates} saved.",
                    13f, R.color.text_muted, false).apply { setPadding(0, dp(6), 0, dp(14)) })
                sheet.addView(pill("Retry", true) { startDownloadAll() })
                if (f.saved + f.duplicates > 0)
                    sheet.addView(pill("Open library", false) { openLibrary() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
        reveal(if (ok) 6000 else 0)
    }

    private fun thumbRow(files: List<java.io.File>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        files.forEach { f ->
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(color(R.color.border), 10); clipToOutline = true
            }
            addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(6) })
            Thread {
                val b = decodeSampled(f, 128, 128)
                runOnUiThread { iv.setImageBitmap(b) }
            }.start()
        }
    }

    private fun render(s: CaptureController.State) {
        when (s) {
            is CaptureController.State.Scanning -> showProgress(0, 0, 0, true)
            is CaptureController.State.Running -> showProgress(s.done, s.total, s.failed, false)
            is CaptureController.State.Finished -> showFinished(s)
        }
    }

    private fun openLibrary() {
        hideSheet()
        startActivity(Intent(this, LibraryActivity::class.java))
    }

    private fun startDownloadAll() {
        if (!bridgeReady) {
            Toast.makeText(this, "Please update Android System WebView", Toast.LENGTH_LONG).show()
            return
        }
        capture.start {
            webView.evaluateJavascript("window.__pcsFlush && window.__pcsFlush()", null)
        }
    }

    // ---------- WebView ----------

    /** Clears cookies, DOM storage/IndexedDB, cache, history and form data. Saved images are not touched. */
    private fun wipeWebData(then: (() -> Unit)? = null) {
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        CookieManager.getInstance().removeAllCookies { then?.invoke() }
    }

    private fun setupWebView() {
        webView.setBackgroundColor(color(R.color.page_bg))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true) // no onCreateWindow: popups and target=_blank are dropped
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        // The page's own download links would write to public storage. Not wanted.
        webView.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "Use the handle at the bottom \u2192 Download All", Toast.LENGTH_SHORT).show()
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressLine.animate().cancel()
                    progressLine.visibility = View.VISIBLE
                    progressLine.alpha = 1f
                    progressLine.scaleX = newProgress / 100f
                } else {
                    progressLine.animate().alpha(0f).setDuration(200).withEndAction {
                        progressLine.visibility = View.GONE
                    }.start()
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return !isAllowedUrl(request.url.toString())
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !isAllowedUrl(url)

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loadFailed) offline.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (errorCode == ERROR_UNKNOWN) return
                loadFailed = true
                offline.visibility = View.VISIBLE
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                loadFailed = true
                offline.visibility = View.VISIBLE
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
                recreate()
                return true
            }
        }
    }

    /**
     * Narrow bridge: one JS object ("shell"), one method (postMessage(string)), injected only into
     * frames on perchance.org origins. Native only parses {t,id,mime,b64}; no paths or URLs from the page.
     */
    private fun setupBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val rules = setOf("https://perchance.org", "https://*.perchance.org")
        fun asset(n: String) = assets.open(n).bufferedReader().use { it.readText() }
        // Hides Perchance's own nav/links and renames "private gallery" to "gallery" (all frames).
        WebViewCompat.addDocumentStartJavaScript(webView, asset("ui.js"), rules)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, "shell", rules) { _, message, _, _, _ ->
            message.data?.let { d ->
                if (d.startsWith("{\"t\":\"dom\"")) onDomPart(d) else capture.onMessage(d)
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, asset("capture.js"), rules)
        if (DEV_TOOLS) WebViewCompat.addDocumentStartJavaScript(webView, asset("dom-dump.js"), rules)
        bridgeReady = true
    }

    /** Main-frame navigation is locked to the generator page. Iframes and sub-resources are not restricted. */
    private fun isAllowedUrl(url: String?): Boolean {
        val u = try { Uri.parse(url ?: return false) } catch (_: Exception) { return false }
        val host = u.host ?: return false
        val path = u.path ?: ""
        return u.scheme == "https" && (host == "perchance.org" || host == "www.perchance.org") &&
            (path == GENERATOR_PATH || path.startsWith("$GENERATOR_PATH/"))
    }

    // ---------- lifecycle ----------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideBars()
        ui.postDelayed({ hideBars() }, 400) // some launchers restore the bars right after the splash handoff
    }
    override fun onPause() { super.onPause(); webView.onPause(); CookieManager.getInstance().flush() }

    override fun onDestroy() {
        if (isFinishing) wipeWebData()
        ui.removeCallbacksAndMessages(null)
        capture.cancel()
        super.onDestroy()
    }

    companion object {
        /** Dev tools (long-press the edge tab). Set to false before shipping. */
        const val DEV_TOOLS = true
        const val GENERATOR_PATH = "/ai-text-to-image-generator"
        const val START_URL = "https://perchance.org$GENERATOR_PATH"
    }
}
