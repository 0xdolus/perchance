package com.perchance.shell.download

import android.util.Base64
import com.perchance.shell.storage.PrivateStorageManager
import com.perchance.shell.web.WebMessageBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

class DownloadManager(
    private val storage: PrivateStorageManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val pending = ConcurrentLinkedQueue<ImagePayload>()
    private var total = 0
    private var done = 0
    private var failed = 0
    private var cancelled = false

    data class ImagePayload(val id: String, val mime: String, val base64: String)

    fun onImageReceived(id: String, mime: String, base64: String) {
        pending.add(ImagePayload(id, mime, base64))
    }

    fun startScanAndDownload(bridge: WebMessageBridge) {
        cancelled = false
        pending.clear()
        total = 0
        done = 0
        failed = 0
        _state.value = DownloadState.Scanning

        scope.launch {
            // Ask frames to flush
            bridge.requestFlush()
            // Give a short window for messages to arrive
            kotlinx.coroutines.delay(1200)

            val items = pending.toList()
            total = items.size
            if (total == 0) {
                _state.value = DownloadState.Done(0)
                return@launch
            }

            _state.value = DownloadState.Running(0, total)

            for (item in items) {
                if (cancelled) break
                try {
                    val bytes = Base64.decode(item.base64, Base64.DEFAULT)
                    storage.save(bytes, item.mime)
                    done++
                } catch (_: Exception) {
                    failed++
                }
                _state.value = DownloadState.Running(done, total)
            }

            if (cancelled) {
                _state.value = DownloadState.Idle
            } else if (failed > 0) {
                _state.value = DownloadState.Failed(done, failed)
            } else {
                _state.value = DownloadState.Done(done)
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun retryFailed() {
        // Simple re-scan for now
        _state.value = DownloadState.Idle
    }
}
