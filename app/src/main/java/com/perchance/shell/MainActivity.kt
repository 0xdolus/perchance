package com.perchance.shell

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.perchance.shell.download.DownloadManager
import com.perchance.shell.download.DownloadState
import com.perchance.shell.gesture.GestureOverlayView
import com.perchance.shell.storage.PrivateStorageManager
import com.perchance.shell.ui.SheetController
import com.perchance.shell.web.PerchanceWebClient
import com.perchance.shell.web.WebMessageBridge
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var gestureOverlay: GestureOverlayView
    private lateinit var sheetContainer: FrameLayout
    private lateinit var fullContainer: FrameLayout

    private lateinit var downloadManager: DownloadManager
    private lateinit var storageManager: PrivateStorageManager
    private lateinit var sheetController: SheetController
    private lateinit var webBridge: WebMessageBridge

    private var currentState: UiState = UiState.Web

    sealed class UiState {
        object Web : UiState()
        object Bar : UiState()
        object Confirm : UiState()
        object Progress : UiState()
        object Done : UiState()
        object Library : UiState()
        object Detail : UiState()
        object NetworkError : UiState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        gestureOverlay = findViewById(R.id.gestureOverlay)
        sheetContainer = findViewById(R.id.sheetContainer)
        fullContainer = findViewById(R.id.fullContainer)

        storageManager = PrivateStorageManager(this)
        downloadManager = DownloadManager(storageManager)
        sheetController = SheetController(this, sheetContainer, fullContainer)
        webBridge = WebMessageBridge(webView, downloadManager)

        setupWebView()
        setupGesture()
        setupBackHandler()
        observeDownloadState()

        loadGenerator()
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

        webView.webViewClient = PerchanceWebClient(
            onPageFinished = { /* optional */ },
            onError = { showNetworkError() }
        )
        webView.webChromeClient = android.webkit.WebChromeClient()

        webBridge.attach()
    }

    private fun setupGesture() {
        gestureOverlay.onThreeFingerSwipeUp = {
            if (currentState == UiState.Web) {
                showBar()
            }
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentState) {
                    UiState.Bar, UiState.Confirm, UiState.Done -> hideSheets()
                    UiState.Progress -> {
                        // ask cancel
                        sheetController.showCancelDownloadDialog {
                            downloadManager.cancel()
                            hideSheets()
                        }
                    }
                    UiState.Detail -> showLibrary()
                    UiState.Library -> hideFull()
                    UiState.NetworkError -> { /* stay */ }
                    UiState.Web -> {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            finish()
                        }
                    }
                }
            }
        })
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            downloadManager.state.collectLatest { state ->
                when (state) {
                    is DownloadState.Idle -> {}
                    is DownloadState.Scanning -> sheetController.showScanning()
                    is DownloadState.Running -> {
                        currentState = UiState.Progress
                        sheetController.showProgress(state.done, state.total)
                    }
                    is DownloadState.Done -> {
                        currentState = UiState.Done
                        sheetController.showDone(state.savedCount) {
                            showLibrary()
                        }
                    }
                    is DownloadState.Failed -> {
                        sheetController.showFailed(state.saved, state.failed) {
                            downloadManager.retryFailed()
                        }
                    }
                }
            }
        }
    }

    private fun loadGenerator() {
        currentState = UiState.Web
        webView.loadUrl(ALLOWED_URL)
    }

    private fun showBar() {
        currentState = UiState.Bar
        sheetController.showBar(
            onDownload = { startDownload() },
            onLibrary = { showLibrary() }
        )
    }

    private fun startDownload() {
        currentState = UiState.Confirm
        // For now trigger scan + download. Full confirm sheet can be added later.
        downloadManager.startScanAndDownload(webBridge)
    }

    private fun showLibrary() {
        currentState = UiState.Library
        sheetController.hideAllSheets()
        fullContainer.visibility = View.VISIBLE
        sheetController.showLibrary(
            storageManager.listImages(),
            onClose = { hideFull() },
            onImageClick = { path -> showDetail(path) }
        )
    }

    private fun showDetail(path: String) {
        currentState = UiState.Detail
        sheetController.showDetail(path) {
            storageManager.delete(path)
            showLibrary()
        }
    }

    private fun hideSheets() {
        currentState = UiState.Web
        sheetController.hideAllSheets()
    }

    private fun hideFull() {
        currentState = UiState.Web
        fullContainer.visibility = View.GONE
        fullContainer.removeAllViews()
    }

    private fun showNetworkError() {
        currentState = UiState.NetworkError
        sheetController.showNetworkError { loadGenerator() }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val ALLOWED_URL = "https://perchance.org/ai-text-to-image-generator"
        val ALLOWED_HOSTS = setOf("perchance.org", "www.perchance.org")
    }
}
