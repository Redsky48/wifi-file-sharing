package com.wifishare.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Two-way mirror between the system clipboard and the WiFi Share server.
 *
 * On the phone side we observe primary-clip changes via
 * [ClipboardManager.OnPrimaryClipChangedListener] and push them out as
 * `clipboard.changed` events. HTTP clients can also push text via
 * PUT /api/clipboard.
 *
 * Privacy note: Android 10+ heavily restricts background clipboard
 * reads — the system only delivers OnPrimaryClipChanged when *our* app
 * is in the foreground or running a foreground service. Our ServerService
 * already runs as a foreground service, so reads work while the server
 * is up. We do NOT poll — only the listener — so we don't trip the
 * "X read your clipboard" toast on Android 12+.
 */
object ClipboardBridge {

    /**
     * Most recently observed clipboard text. Empty string if the
     * clipboard contains something we can't read as text (image, files,
     * etc.) or hasn't been touched since the server started.
     */
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    @Volatile
    private var bound: ClipboardManager? = null
    @Volatile
    private var appContext: Context? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    fun attach(context: Context) {
        if (bound != null) return
        val ctx = context.applicationContext
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        bound = cm
        appContext = ctx
        // Seed initial value so HTTP clients see whatever's there now.
        readCurrent(cm, ctx)?.let {
            _text.value = it
            // Don't emit an event on attach — that would spam clients
            // every time the server restarts. Events are for *changes*.
        }
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            val now = readCurrent(cm, ctx) ?: return@OnPrimaryClipChangedListener
            if (now == _text.value) return@OnPrimaryClipChangedListener
            _text.value = now
            PhoneEvents.push("clipboard.changed", mapOf(
                "length" to now.length,
                // Don't push the full text in the event — it can be huge
                // (megabyte-scale paste buffers) and event subscribers
                // may not need the content. Truncate to a preview; they
                // can GET /api/clipboard for the full payload.
                "preview" to now.take(120),
            ))
        }.also { cm.addPrimaryClipChangedListener(it) }
    }

    fun detach() {
        val cm = bound ?: return
        listener?.let { runCatching { cm.removePrimaryClipChangedListener(it) } }
        listener = null
        bound = null
        appContext = null
    }

    /** Snapshot the cached clipboard text (whatever the listener last saw). */
    fun snapshot(): String = _text.value

    /**
     * Read the current system clipboard *right now* instead of trusting
     * the listener's cached value. On Android 10+ this only succeeds if
     * the calling process has an active Accessibility Service — useful
     * when the user has just enabled the service and the listener
     * hasn't had a chance to fire yet.
     *
     * Also keeps the cached snapshot in sync as a side-effect.
     */
    fun forceRead(context: Context): String {
        val cm = bound ?: context.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return _text.value
        val now = readCurrent(cm, context.applicationContext)
        if (!now.isNullOrEmpty() && now != _text.value) _text.value = now
        return _text.value
    }

    /** Diagnostic: is the listener actually attached and bound? */
    fun isBound(): Boolean = bound != null

    /**
     * Programmatically set the system clipboard. Must run on the main
     * looper — Android throws if [setPrimaryClip] is called off-thread.
     */
    fun setText(context: Context, value: String) {
        val cm = bound ?: context.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            runCatching {
                cm.setPrimaryClip(ClipData.newPlainText("WiFi Share", value))
                // setPrimaryClip will fire our own listener — no need to
                // emit here, that would double-publish.
            }
        }
    }

    private fun readCurrent(cm: ClipboardManager, ctx: Context): String? {
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        // Concatenate items in case the clip has multiple — rare for text.
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = runCatching { item.coerceToText(ctx) }.getOrNull()
                ?: item.text
                ?: continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }
}
