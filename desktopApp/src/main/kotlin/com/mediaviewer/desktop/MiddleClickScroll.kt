package com.mediaviewer.desktop

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Autoscroll with the mouse's middle button, like Firefox: press it and
 * move away from where it was pressed — whatever is under that spot
 * scrolls, faster the farther the pointer is. Drag with it held and let go
 * to stop; or click it, move, and click anything to stop. The pointer
 * becomes a four-way arrow meanwhile. The scrolling is sent to the window
 * as ordinary mouse-wheel turns, so every list, grid and page scrolls.
 */
object MiddleClickScroll {
    private var active = false
    private var sticky = false
    private var moved = false
    private var target: Component? = null
    private var originLocal = Point()
    private var originScreen = Point()
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastTick = 0L
    private var cursorWindow: Window? = null
    private var savedCursor: Cursor? = null
    /** A wheel turn of 1 scrolls about this many pixels. */
    private const val PX_PER_NOTCH = 48.0

    private val timer = Timer(16) { tick() }

    fun install() {
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            if (e !is MouseEvent || e is MouseWheelEvent) return@addAWTEventListener
            when (e.id) {
                MouseEvent.MOUSE_PRESSED -> {
                    if (active && sticky) { stop(); e.consume(); return@addAWTEventListener }
                    if (e.button == MouseEvent.BUTTON2) start(e)
                }
                MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_MOVED -> if (active) {
                    if (hypot((e.xOnScreen - originScreen.x).toDouble(), (e.yOnScreen - originScreen.y).toDouble()) > 12) moved = true
                }
                MouseEvent.MOUSE_RELEASED -> if (active && e.button == MouseEvent.BUTTON2) {
                    // A click (no drag): keep scrolling until the next click.
                    if (moved) stop() else sticky = true
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK)
    }

    private fun start(e: MouseEvent) {
        active = true; sticky = false; moved = false
        target = e.component
        originLocal = e.point
        originScreen = e.locationOnScreen
        carryX = 0.0; carryY = 0.0; lastTick = System.nanoTime()
        val w = SwingUtilities.getWindowAncestor(e.component) ?: (e.component as? Window)
        cursorWindow = w
        savedCursor = w?.cursor
        w?.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        timer.start()
    }

    private fun stop() {
        if (!active) return
        active = false; sticky = false
        timer.stop()
        cursorWindow?.cursor = savedCursor ?: Cursor.getDefaultCursor()
        cursorWindow = null; target = null
    }

    /** Pixels per second for a pointer [d] px from the start (none near it). */
    private fun speed(d: Double): Double {
        val a = abs(d)
        if (a <= 12) return 0.0
        val over = a - 12
        return (if (d < 0) -1 else 1) * over * 7 * (1 + over / 300)
    }

    private fun tick() {
        val c = target ?: return stop()
        if (!c.isShowing) return stop()
        val now = System.nanoTime()
        val dt = ((now - lastTick) / 1e9).coerceIn(0.0, 0.1)
        lastTick = now
        val p = MouseInfo.getPointerInfo()?.location ?: return
        carryX += speed((p.x - originScreen.x).toDouble()) * dt
        carryY += speed((p.y - originScreen.y).toDouble()) * dt
        if (abs(carryY) >= 1) { wheel(c, carryY / PX_PER_NOTCH, horizontal = false); carryY = 0.0 }
        if (abs(carryX) >= 1) { wheel(c, carryX / PX_PER_NOTCH, horizontal = true); carryX = 0.0 }
    }

    private fun wheel(c: Component, notches: Double, horizontal: Boolean) {
        val e = MouseWheelEvent(
            c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
            if (horizontal) InputEvent.SHIFT_DOWN_MASK else 0,
            originLocal.x, originLocal.y, originScreen.x, originScreen.y,
            0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1,
            notches.toInt(), notches
        )
        c.dispatchEvent(e)
    }
}
