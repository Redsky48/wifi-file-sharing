package com.wifishare.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Lets HTTP clients inject taps, swipes, and global key actions (Back /
 * Home / Recents) into Android via the Accessibility API.
 *
 * Why Accessibility instead of [android.os.SystemClock] / direct input
 * injection? `INJECT_EVENTS` is a signature-level permission — only
 * system apps can hold it. Accessibility Service is the *sanctioned*
 * way for third-party apps to drive the UI; user enables it once via
 * Settings → Accessibility → WiFi Share remote input.
 *
 * The service stays running across reboots once enabled. We don't
 * connect it to the HTTP layer with a binder — instead the singleton
 * pattern exposes [instance] which the HTTP handlers call directly.
 */
class RemoteInputService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't observe events — we only inject. But the abstract
        // method must be overridden, so it's a no-op.
    }

    override fun onInterrupt() {
        // System asked us to stop ongoing accessibility activity. Our
        // gestures are one-shot, so nothing to interrupt.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Also bind the clipboard listener here — Android 10+ blocks
        // background clipboard reads from regular foreground services,
        // but Accessibility services have an explicit OS bypass. So
        // once the user enables this service, system-wide clipboard
        // sync starts working even while the phone shows other apps.
        com.wifishare.server.ClipboardBridge.attach(applicationContext)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /**
     * Single-finger tap at the given screen coordinate. Coordinates are
     * in raw display pixels — caller is responsible for matching the
     * screen size reported via /api/screen/status. Default duration
     * (40 ms) feels like a real click; longer values would look like a
     * long-press to most UIs.
     */
    fun tap(x: Float, y: Float, durationMs: Long = 40L): Boolean =
        dispatchPath(Path().apply { moveTo(x, y) }, 0L, durationMs)

    // ── Continued-stroke drag pipeline ─────────────────────────────
    // Android Accessibility supports chained gestures via
    // [GestureDescription.StrokeDescription.continueStroke]. Each
    // subsequent stroke begins exactly where the previous one ended
    // and the system never lifts the finger — that's what makes a
    // scroll-like drag feel smooth instead of stop-start jittery.
    //
    // The state lives in this service: a single in-progress stroke
    // and the last touchpoint. dragStart resets it, dragMove chains a
    // new segment, dragEnd chains the final non-continuing segment.
    // All three are @Synchronized because the HTTP server thread, the
    // gesture-callback handler, and the timeout watchdog can all hit
    // them concurrently.

    private val dragLock = Any()
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var currentX: Float = 0f
    private var currentY: Float = 0f

    fun dragStart(x: Float, y: Float, durationMs: Long = 40L): Boolean {
        synchronized(dragLock) {
            // Initial path needs at least one lineTo — a moveTo alone
            // would be rejected as zero-length. Inject a 1 px shim to
            // keep the system happy without producing visible motion.
            val path = Path().apply { moveTo(x, y); lineTo(x + 0.001f, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs, true)
            currentStroke = stroke
            currentX = x + 0.001f
            currentY = y
            return dispatchSingle(stroke)
        }
    }

    fun dragMove(x: Float, y: Float, durationMs: Long = 40L): Boolean {
        synchronized(dragLock) {
            val prev = currentStroke ?: return false
            val path = Path().apply { moveTo(currentX, currentY); lineTo(x, y) }
            val newStroke = prev.continueStroke(path, 0L, durationMs, true)
            currentStroke = newStroke
            currentX = x
            currentY = y
            return dispatchSingle(newStroke)
        }
    }

    fun dragEnd(x: Float, y: Float, durationMs: Long = 40L): Boolean {
        synchronized(dragLock) {
            val prev = currentStroke ?: return false
            val path = Path().apply { moveTo(currentX, currentY); lineTo(x, y) }
            val finalStroke = prev.continueStroke(path, 0L, durationMs, false)
            currentStroke = null
            return dispatchSingle(finalStroke)
        }
    }

    fun dragCancel() {
        synchronized(dragLock) { currentStroke = null }
    }

    private fun dispatchSingle(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
    }

    fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 250L,
    ): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchPath(path, 0L, durationMs)
    }

    /**
     * Vertical scroll wheel — converted into a short fling-like swipe
     * centered on the given point. dy > 0 scrolls down (content moves
     * up); dy < 0 scrolls up. Magnitude is the swipe length in pixels.
     */
    fun scroll(cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        val path = Path().apply {
            // Start "above" the target and drag opposite — matches how
            // touch scroll works in most apps (drag finger up = content
            // scrolls down).
            moveTo(cx - dx / 2f, cy - dy / 2f)
            lineTo(cx + dx / 2f, cy + dy / 2f)
        }
        return dispatchPath(path, 0L, 120L)
    }

    /**
     * Maps a few well-known PC keys to Android's global actions. Free-
     * form text input would need an InputMethodService rather than the
     * Accessibility API — out of scope for this initial pass.
     */
    fun globalKey(action: String): Boolean = when (action.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents", "overview" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power", "power_dialog" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock", "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else -> false
    }

    private fun dispatchPath(path: Path, startMs: Long, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        // dispatchGesture wants a Handler for the callback — pass main looper.
        return dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
    }

    companion object {
        @Volatile
        var instance: RemoteInputService? = null
            private set

        val isAvailable: Boolean
            get() = instance != null

        /**
         * Reads <c>Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES</c> to
         * find out whether the user has flipped our service on. Works
         * BEFORE the service is actually bound, which is the gap
         * [isAvailable] can't cover — we want to know on app launch
         * if the permission was previously granted.
         */
        fun isEnabled(context: android.content.Context): Boolean {
            val expected = "${context.packageName}/" + RemoteInputService::class.java.name
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any {
                it.equals(expected, ignoreCase = true) ||
                    // Some launchers store the relative form (".RemoteInputService")
                    it.equals("${context.packageName}/.input.RemoteInputService", ignoreCase = true)
            }
        }

        /** Sends the user straight to the Accessibility settings page. */
        fun openSettings(context: android.content.Context) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            ).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
