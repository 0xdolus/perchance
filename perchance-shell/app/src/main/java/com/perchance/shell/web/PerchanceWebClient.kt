package com.perchance.shell.web

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.perchance.shell.MainActivity

class PerchanceWebClient(
    private val onPageFinished: () -> Unit = {},
    private val onError: () -> Unit = {}
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val host = request?.url?.host ?: return true
        return if (host in MainActivity.ALLOWED_HOSTS || host.endsWith(".perchance.org")) {
            false // allow
        } else {
            true // block everything else
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame == true) {
            onError()
        }
    }
}
