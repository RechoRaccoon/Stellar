package com.mediaviewer.ui

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Autoscroll with the mouse's middle button, like Firefox: press it and
 * move away from where it was pressed — whatever is under that spot
 * scrolls, faster the farther the pointer is. Drag with it held and let go
 * to stop; or click it and move, then click anything to stop. A small
 * marker sits where it started meanwhile. Scrolling is sent as ordinary
 * mouse-wheel events, so every list, grid and page scrolls with it.
 */
class MiddleClickScroll(private val activity: Activity) {
    private var active = false
    private var sticky = false
    private var moved = false
    private var originX = 0f
    private var originY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var lastFrame = 0L
    private var carryX = 0f
    private var carryY = 0f
    private var marker: View? = null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            val dt = if (lastFrame == 0L) 1f / 60f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
            lastFrame = frameTimeNanos
            carryX += speed(pointerX - originX) * dt
            carryY += speed(pointerY - originY) * dt
            val sx = carryX.toInt().toFloat()
            val sy = carryY.toInt().toFloat()
            if (sx != 0f || sy != 0f) {
                carryX -= sx; carryY -= sy
                scrollBy(sx, sy)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Pixels per second for a pointer [d] px from the start (none near it). */
    private fun speed(d: Float): Float {
        val density = activity.resources.displayMetrics.density
        val dead = 12f * density
        val a = abs(d)
        if (a <= dead) return 0f
        val over = (a - dead) / density
        val dpPerSec = over * 7f * (1f + over / 300f)
        return (if (d < 0) -1f else 1f) * dpPerSec * density
    }

    private fun scrollBy(dx: Float, dy: Float) {
        val factorV = runCatching { android.view.ViewConfiguration.get(activity).scaledVerticalScrollFactor }.getOrDefault(64f)
        val factorH = runCatching { android.view.ViewConfiguration.get(activity).scaledHorizontalScrollFactor }.getOrDefault(64f)
        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = originX; y = originY
            // (A wheel's "up" is positive: content moving down the screen.)
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / factorV)
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx / factorH)
        })
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        runCatching { activity.window.decorView.dispatchGenericMotionEvent(e) }
        e.recycle()
    }

    private fun isMouse(ev: MotionEvent) = ev.isFromSource(InputDevice.SOURCE_MOUSE)

    /** From the activity's dispatchTouchEvent: true when it was ours. */
    fun onTouch(ev: MotionEvent): Boolean {
        if (!isMouse(ev)) return false
        val middle = (ev.buttonState and MotionEvent.BUTTON_TERTIARY) != 0
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (active && sticky) { stop(); return true }
                if (!middle) return false
                start(ev.x, ev.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return false
                track(ev.x, ev.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!active) return false
                // A click (no drag): keep scrolling until the next click.
                if (moved || ev.actionMasked == MotionEvent.ACTION_CANCEL) stop() else sticky = true
                return true
            }
        }
        return active
    }

    /** From the activity's dispatchGenericMotionEvent (hover, wheel). */
    fun onGeneric(ev: MotionEvent): Boolean {
        if (!active || !isMouse(ev)) return false
        return when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> { track(ev.x, ev.y); true }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> true
            // (A real wheel turn stops it, like Firefox.)
            MotionEvent.ACTION_SCROLL -> { stop(); false }
            else -> false
        }
    }

    private fun track(x: Float, y: Float) {
        pointerX = x; pointerY = y
        val density = activity.resources.displayMetrics.density
        if (hypot(x - originX, y - originY) > 12f * density) moved = true
    }

    private fun start(x: Float, y: Float) {
        active = true; sticky = false; moved = false
        originX = x; originY = y; pointerX = x; pointerY = y
        carryX = 0f; carryY = 0f; lastFrame = 0L
        showMarker()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun stop() {
        if (!active) return
        active = false; sticky = false
        Choreographer.getInstance().removeFrameCallback(frame)
        hideMarker()
    }

    private fun showMarker() {
        val root = activity.window.decorView as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        val size = (30 * density).toInt()
        val view = object : View(activity) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
            private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density }
            private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            override fun onDraw(canvas: Canvas) {
                val r = width / 2f
                canvas.drawCircle(r, r, r - line.strokeWidth, fill)
                canvas.drawCircle(r, r, r - line.strokeWidth, line)
                val a = r * 0.32f
                fun tri(cx: Float, cy: Float, dx: Float, dy: Float) {
                    val p = android.graphics.Path()
                    p.moveTo(cx + dx * a, cy + dy * a)
                    p.lineTo(cx - dy * a * 0.8f, cy + dx * a * 0.8f)
                    p.lineTo(cx + dy * a * 0.8f, cy - dx * a * 0.8f)
                    p.close()
                    canvas.drawPath(p, arrow)
                }
                tri(r, r * 0.42f, 0f, -1f); tri(r, r * 1.58f, 0f, 1f)
                tri(r * 0.42f, r, -1f, 0f); tri(r * 1.58f, r, 1f, 0f)
                canvas.drawCircle(r, r, 2f * density, arrow)
            }
        }
        view.layoutParams = FrameLayout.LayoutParams(size, size)
        view.translationX = originX - size / 2f
        view.translationY = originY - size / 2f
        view.isClickable = false
        view.elevation = 1000f
        runCatching { root.addView(view) }
        marker = view
    }

    private fun hideMarker() {
        val v = marker ?: return
        marker = null
        runCatching { (v.parent as? ViewGroup)?.removeView(v) }
    }
}
