package com.perchance.shell.download

sealed class DownloadState {
    object Idle : DownloadState()
    object Scanning : DownloadState()
    data class Running(val done: Int, val total: Int) : DownloadState()
    data class Done(val savedCount: Int) : DownloadState()
    data class Failed(val saved: Int, val failed: Int) : DownloadState()
}
