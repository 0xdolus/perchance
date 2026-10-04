package com.perchance.shell.gesture

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Transparent overlay that only observes multi-finger gestures.
 * Never consumes events so the WebView underneath still receives all touches.
 */
class GestureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onThreeFingerSwipeUp: (() -> Unit)? = null

    private var startY = 0f
    private var pointerCount = 0
    private var tracking = false

    // Thresholds (dp)
    private val minSwipeDp = 140f
    private val maxHorizontalDp = 80f
    private val density = resources.displayMetrics.density

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // Never consume – always return false so events pass to WebView
        event ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                if (pointerCount == 3) {
                    tracking = true
                    startY = event.getY(0)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && event.pointerCount == 3) {
                    val dy = startY - event.getY(0) // up is positive
                    val dx = abs(event.getX(0) - event.getX(1)) // rough horizontal spread check
                    // simple check; real implementation can average all pointers
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking && pointerCount >= 3) {
                    val endY = event.getY(0)
                    val dy = startY - endY
                    if (dy > minSwipeDp * density) {
                        onThreeFingerSwipeUp?.invoke()
                    }
                }
                tracking = false
                pointerCount = event.pointerCount
            }
        }
        return false // critical: never consume
    }

    // Also intercept but never steal
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
}
