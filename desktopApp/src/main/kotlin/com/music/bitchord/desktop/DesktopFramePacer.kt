package com.music.bitchord.desktop

import kotlinx.coroutines.delay
import org.jetbrains.skiko.SkiaLayer
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.Window
import java.lang.reflect.Field
import java.util.WeakHashMap
import javax.swing.Timer

/*
 * Top-level, not in the object: its enum reads these while the object is still initialising.
 * Overridable for measuring, e.g. -Dbitchord.pace.background=1000.
 */

/** About thirty frames a second: smooth enough for lyrics nobody is scrolling. */
private val IDLE_FRAME_MS: Long = java.lang.Long.getLong("bitchord.pace.idle", 33L)

/** Four frames a second: the playhead and the lyrics still move, at a glance. */
private val BACKGROUND_FRAME_MS: Long = java.lang.Long.getLong("bitchord.pace.background", 250L)

/**
 * Redraws the window as often as the listener can use, and no oftener.
 *
 * Compose redraws the whole window for every frame anything in it asks for, and while a song
 * plays something always asks: the lyrics, a drifting gradient, a shimmer. At the display's rate
 * that is one core of an old laptop spent on a window nobody is looking at — measured on an
 * i3-3217U, the UI thread alone sat at 85–95% with the application in the background.
 *
 * So the rate follows attention. While the pointer or the keyboard is in use the window draws at
 * the display's rate, as before. Focused but left alone it draws at [IDLE_FRAME_MS]; with the
 * focus somewhere else, at [BACKGROUND_FRAME_MS]. Animations run on frame time, so they keep
 * their speed and only lose smoothness.
 *
 * Linux only, and only on the OpenGL redrawer, because the lever is skiko's own frame limiter:
 * that redrawer waits on a [org.jetbrains.skiko.FrameLimiter] after every frame, and the limiter
 * asks a function how long a frame should last. This swaps that function for one that answers by
 * the current pace. skiko has no public setting for it, so the swap is by reflection and guarded:
 * if the fields are not where 0.150 keeps them, nothing is changed and the window draws as it
 * always did.
 */
internal object DesktopFramePacer {

    /**
     * [frameMillis] is the shortest a frame may last, 0 for the display's own; [napMillis] is how
     * long the limiter sleeps at a time while it waits one out. skiko naps a millisecond at a
     * time for precision, a thousand wake-ups a second however slow the frames are, which is a
     * tenth of a core here on its own. A slow pace has no use for the precision.
     */
    private enum class Pace(val frameMillis: Long, val napMillis: Long) {
        ACTIVE(0L, 1L),
        IDLE(IDLE_FRAME_MS, 4L),
        BACKGROUND(BACKGROUND_FRAME_MS, 25L),
    }

    @Volatile
    private var pace = Pace.ACTIVE

    @Volatile
    private var lastInputNanos = System.nanoTime()

    /** Set by the limiter each time it starts waiting out a frame; see [bridge]. */
    @Volatile
    private var limiterTicked = true

    private var started = false
    private var broken = false

    /** The redrawers whose limiter answers to this, weakly: a closed window's goes with it. */
    private val paced = WeakHashMap<Any, Unit>()
    private var bridgeTimer: Timer? = null

    /** Call once, on the event thread. Windows that open later are picked up as they appear. */
    fun start() {
        if (started || !DesktopPlatform.isLinux) return
        started = true
        Toolkit.getDefaultToolkit().addAWTEventListener({ onInput() }, INPUT_EVENTS)
        // Focus arriving by keyboard brings no pointer event with it, and the poll below would
        // leave the window on the slow pace for up to a quarter of a second more.
        Toolkit.getDefaultToolkit().addAWTEventListener({ evaluate() }, AWTEvent.WINDOW_FOCUS_EVENT_MASK)
        Timer(POLL_MS) { poll() }.start()
    }

    private fun onInput() {
        lastInputNanos = System.nanoTime()
        if (pace != Pace.ACTIVE) evaluate()
    }

    private fun poll() {
        if (broken) return
        try {
            for (window in Window.getWindows()) if (window.isShowing) adopt(window)
        } catch (failure: ReflectiveOperationException) {
            giveUp(failure)
            return
        } catch (failure: RuntimeException) {
            giveUp(failure)
            return
        }
        evaluate()
    }

    private fun giveUp(failure: Exception) {
        broken = true
        settle(Pace.ACTIVE)
        DesktopTrackLog.log("frame pacing is off: ${failure.javaClass.simpleName}: ${failure.message}")
    }

    private fun evaluate() {
        if (!DesktopAppearanceSettings.frameSaver.value) {
            settle(Pace.ACTIVE)
            return
        }
        val recentInput = System.nanoTime() - lastInputNanos < ACTIVE_FOR_NANOS
        val focused = Window.getWindows().any { it.isShowing && it.isFocused }
        settle(
            when {
                recentInput -> Pace.ACTIVE
                focused -> Pace.IDLE
                else -> Pace.BACKGROUND
            },
        )
    }

    private fun settle(next: Pace) {
        val previous = pace
        if (next == previous) return
        pace = next
        // Out of the slow pace the limiter may be most of a frame away from noticing, and a
        // window that takes a quarter of a second to answer a click feels broken.
        if (previous == Pace.BACKGROUND) bridge()
    }

    /**
     * Draws frames directly until the limiter is back on the new pace.
     *
     * The limiter reads its pace once per frame and then sleeps that long, so the frame it is
     * already waiting out still lasts the old length. Rather than reach into that wait, this
     * renders on a short timer until the limiter is seen starting its next frame.
     */
    private fun bridge() {
        limiterTicked = false
        bridgeTimer?.stop()
        bridgeTimer = Timer(BRIDGE_MS) { event ->
            if (limiterTicked || pace == Pace.BACKGROUND || broken) {
                (event.source as Timer).stop()
                return@Timer
            }
            for (redrawer in paced.keys.toList()) {
                runCatching { redrawer.javaClass.getMethod("renderImmediately").invoke(redrawer) }
            }
        }.also(Timer::start)
    }

    private fun adopt(component: Component) {
        if (component is SkiaLayer) takeOver(component)
        if (component is Container) for (child in component.components) adopt(child)
    }

    private fun takeOver(layer: SkiaLayer) {
        val redrawer = redrawerOf?.invoke(layer) ?: return
        if (redrawer in paced) return
        // Only the OpenGL redrawer has one; the others pace themselves some other way.
        val limiter = fieldOf(redrawer.javaClass, "frameLimiter")?.get(redrawer) ?: return
        val frameMillis = fieldOf(limiter.javaClass, "frameMillis") ?: return
        val nap = fieldOf(limiter.javaClass, "impreciseDelay") ?: return
        @Suppress("UNCHECKED_CAST")
        val displayFrame = (frameMillis.get(limiter) as? (() -> Long)) ?: return
        if (displayFrame !is PacedFrame) {
            frameMillis.set(limiter, PacedFrame(displayFrame))
            nap.set(limiter, pacedNap)
        }
        paced[redrawer] = Unit
    }

    private fun fieldOf(start: Class<*>, name: String): Field? {
        var type: Class<*>? = start
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (missing: NoSuchFieldException) {
                type = type.superclass
            }
        }
        return null
    }

    /** How long a frame lasts: the display's answer, or the pace's when that is longer. */
    private class PacedFrame(private val displayFrame: () -> Long) : () -> Long {
        override fun invoke(): Long {
            limiterTicked = true
            return maxOf(displayFrame(), pace.frameMillis)
        }
    }

    /** The limiter's sleep, a millisecond at a time as skiko has it unless the pace is slow. */
    private val pacedNap: suspend (Long) -> Unit = { asked -> delay(maxOf(asked, pace.napMillis)) }

    /** `SkiaLayer.redrawer`, which is internal to skiko; null if a later skiko renames it. */
    private val redrawerOf = runCatching { SkiaLayer::class.java.getMethod("getRedrawer\$skiko") }.getOrNull()

    private const val INPUT_EVENTS = AWTEvent.MOUSE_EVENT_MASK or
        AWTEvent.MOUSE_MOTION_EVENT_MASK or
        AWTEvent.MOUSE_WHEEL_EVENT_MASK or
        AWTEvent.KEY_EVENT_MASK

    /** How often focus is looked at and new windows are picked up. Input is noticed at once. */
    private const val POLL_MS = 250

    /** How long after the last key or pointer event the window still counts as in use. */
    private const val ACTIVE_FOR_NANOS = 3_000_000_000L

    private const val BRIDGE_MS = 16
}
