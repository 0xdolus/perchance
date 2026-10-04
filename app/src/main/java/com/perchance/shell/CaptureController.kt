package com.perchance.shell

import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Collects images reported by the in-page capture script (all frames), saves them,
 * and reports progress. The page only ever sends {t, id, mime, b64}; it never picks a path.
 * The capture strategy lives in assets/capture.js and can be swapped without touching this.
 */
class CaptureController(
    private val store: ImageStore,
    private val onState: (State) -> Unit
) {
    sealed class State {
        object Scanning : State()
        data class Running(val done: Int, val total: Int, val failed: Int) : State()
        data class Finished(
            val saved: Int, val duplicates: Int, val failed: Int,
            val total: Int, val lowSpace: Boolean
        ) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var session = 0
    var isActive = false
        private set

    private var total = 0
    private var saved = 0
    private var dup = 0
    private var failed = 0
    private var pending = 0
    private var lowSpace = false

    private val settle = Runnable { if (pending > 0) bump(600) else finish() }

    private fun bump(ms: Long) {
        main.removeCallbacks(settle)
        main.postDelayed(settle, ms)
    }

    fun start(flush: () -> Unit) {
        if (isActive) return
        session++
        isActive = true
        total = 0; saved = 0; dup = 0; failed = 0; pending = 0; lowSpace = false
        onState(State.Scanning)
        val s = session
        io.execute {
            store.ensureIndexed()
            main.post {
                if (s == session && isActive) {
                    flush()
                    bump(3500) // frames that never answer end the scan
                }
            }
        }
    }

    fun cancel() {
        session++
        isActive = false
        main.removeCallbacks(settle)
    }

    /** Called on the main thread from the WebMessage listener. */
    fun onMessage(raw: String) {
        if (!isActive || raw.length > MAX_MESSAGE) return
        try {
            val j = JSONObject(raw)
            when (j.optString("t")) {
                "count" -> total += j.optInt("n", 0).coerceIn(0, 500)
                "fail" -> failed++
                "img" -> {
                    val b64 = j.optString("b64")
                    if (b64.isEmpty()) failed++ else queueSave(b64)
                }
                else -> return
            }
        } catch (_: Exception) {
            return
        }
        emit()
        bump(1800)
    }

    private fun queueSave(b64: String) {
        pending++
        val s = session
        io.execute {
            val r = try {
                store.save(Base64.decode(b64, Base64.DEFAULT))
            } catch (_: OutOfMemoryError) {
                ImageStore.Result.IO
            } catch (_: Exception) {
                ImageStore.Result.INVALID
            }
            main.post {
                if (s != session) return@post
                pending--
                when (r) {
                    ImageStore.Result.SAVED -> saved++
                    ImageStore.Result.DUPLICATE -> dup++
                    else -> {
                        failed++
                        if (r == ImageStore.Result.NO_SPACE) lowSpace = true
                    }
                }
                emit()
            }
        }
    }

    private fun emit() {
        val done = saved + dup + failed
        onState(State.Running(done, maxOf(total, done + pending), failed))
    }

    private fun finish() {
        isActive = false
        onState(State.Finished(saved, dup, failed, total, lowSpace))
    }

    companion object {
        private const val MAX_MESSAGE = 40 * 1024 * 1024
    }
}
