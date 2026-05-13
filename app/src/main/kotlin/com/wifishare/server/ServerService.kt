package com.wifishare.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wifishare.MainActivity
import com.wifishare.R
import com.wifishare.WifiShareApp
import com.wifishare.util.WifiMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class ServerService : Service() {

    private var server: FileServer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wifiWatchJob: Job? = null
    private var transfersWatchJob: Job? = null
    private var queueWatchJob: Job? = null
    private var notifyWatchJob: Job? = null
    private var mdns: MdnsAdvertiser? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_CANCEL_PENDING -> {
                Queue.clearAll()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val folder = intent.getStringExtra(EXTRA_FOLDER_URI)?.let(Uri::parse) ?: run {
            stopSelf()
            return
        }
        val port = intent.getIntExtra(EXTRA_PORT, 8080)
        val allowUploads = intent.getBooleanExtra(EXTRA_ALLOW_UPLOADS, true)
        val allowDelete = intent.getBooleanExtra(EXTRA_ALLOW_DELETE, false)
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()

        val ip = WifiMonitor.currentIp() ?: run {
            updateState(State.Error("Connect to WiFi first"))
            stopSelf()
            return
        }

        server?.stop()
        val srv = FileServer(
            applicationContext,
            port,
            FileServer.Config(folder, allowUploads, allowDelete, password),
        )
        try {
            srv.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            updateState(State.Error(e.message ?: "start failed"))
            stopSelf()
            return
        }

        server = srv
        val url = "http://$ip:$port"
        acquireLocks()
        startForegroundCompat(buildNotification(url))
        updateState(State.Running(url, port))

        mdns = MdnsAdvertiser(applicationContext).also {
            it.start(
                port = port,
                deviceName = android.os.Build.MODEL ?: "Android",
                authRequired = password.isNotEmpty(),
            )
        }

        wifiWatchJob?.cancel()
        wifiWatchJob = scope.launch {
            WifiMonitor.state.drop(1).collect { wifi ->
                if (wifi is WifiMonitor.State.Disconnected) {
                    updateState(State.Error("WiFi disconnected"))
                    handleStop()
                }
            }
        }

        transfersWatchJob?.cancel()
        transfersWatchJob = scope.launch {
            Transfers.events.collect { event ->
                postTransferNotification(event)
            }
        }

        queueWatchJob?.cancel()
        queueWatchJob = scope.launch {
            Queue.state.collect { items -> updatePendingNotification(items.size) }
        }

        notifyWatchJob?.cancel()
        notifyWatchJob = scope.launch {
            Notifications.events.collect { n -> postCustomNotification(n.title, n.body) }
        }
    }

    private fun postCustomNotification(title: String, body: String) {
        val tap = PendingIntent.getActivity(
            this,
            (title + body).hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, WifiShareApp.CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(nextTransferId(), notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied
        }
    }

    private fun updatePendingNotification(count: Int) {
        val mgr = NotificationManagerCompat.from(this)
        if (count == 0) {
            mgr.cancel(PENDING_NOTIF_ID)
            return
        }
        val tap = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelAll = PendingIntent.getService(
            this,
            11,
            Intent(this, ServerService::class.java).setAction(ACTION_CANCEL_PENDING),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, WifiShareApp.CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_pending_title))
            .setContentText(getString(R.string.notification_pending_text, count))
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Cancel all", cancelAll)
            .build()
        try {
            mgr.notify(PENDING_NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied
        }
    }

    private fun postTransferNotification(event: TransferEvent) {
        val (text, icon) = when (event) {
            is TransferEvent.Uploaded ->
                getString(R.string.notification_uploaded, event.name) to
                    android.R.drawable.stat_sys_download_done
            is TransferEvent.Downloaded ->
                getString(R.string.notification_downloaded, event.name) to
                    android.R.drawable.stat_sys_upload_done
            is TransferEvent.Deleted -> return
        }
        val tap = PendingIntent.getActivity(
            this,
            event.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, WifiShareApp.CHANNEL_TRANSFERS)
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setGroup(GROUP_TRANSFERS)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(nextTransferId(), notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; silently skip
        }
    }

    /**
     * Hold WiFi + CPU partially awake while serving. Without these locks the
     * WiFi radio enters power-save (PSP) mode when the screen turns off, and
     * the kernel may pause the app's threads — both kill incoming sockets,
     * which made the Windows companion see the phone as "disconnected"
     * within ~10 seconds of the screen locking.
     */
    private fun acquireLocks() {
        releaseLocks()
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "wifishare:wifi",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wifishare:cpu",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun nextTransferId(): Int {
        val n = transferIdCounter.incrementAndGet()
        if (n > Int.MAX_VALUE - 100) transferIdCounter.set(TRANSFER_ID_BASE)
        return n
    }

    private fun handleStop() {
        wifiWatchJob?.cancel()
        wifiWatchJob = null
        transfersWatchJob?.cancel()
        transfersWatchJob = null
        queueWatchJob?.cancel()
        queueWatchJob = null
        notifyWatchJob?.cancel()
        notifyWatchJob = null

        // Drop the pending notification (queue won't be picked up after stop).
        NotificationManagerCompat.from(this).cancel(PENDING_NOTIF_ID)

        // Send mDNS goodbye FIRST so connected clients see the service go
        // offline before their HTTP polls hit a closed socket. They get a
        // Lost event in <1s and can show "disconnected" immediately.
        mdns?.stop()
        mdns = null

        // Mark every active client as having received a server-going-down
        // signal — the FileServer will reply 503 to any in-flight request
        // during the grace window, so clients give up fast.
        ShutdownSignal.armed = true

        scope.launch {
            // Brief grace so the multicast goodbye actually leaves the device
            // and any in-flight client HTTP requests can drain.
            delay(400)

            ShutdownSignal.armed = false
            releaseLocks()
            server?.stop()
            server = null
            if (state.value !is State.Error) updateState(State.Stopped)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        wifiWatchJob?.cancel()
        transfersWatchJob?.cancel()
        scope.cancel()
        mdns?.stop()
        mdns = null
        releaseLocks()
        server?.stop()
        server = null
        if (state.value !is State.Error) updateState(State.Stopped)
        super.onDestroy()
    }

    private fun buildNotification(url: String): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, WifiShareApp.CHANNEL_SERVER)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running, url))
            .setContentIntent(tap)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    sealed class State {
        data object Stopped : State()
        data class Running(val url: String, val port: Int) : State()
        data class Error(val message: String) : State()
    }

    companion object {
        const val ACTION_START = "com.wifishare.START"
        const val ACTION_STOP = "com.wifishare.STOP"
        const val ACTION_CANCEL_PENDING = "com.wifishare.CANCEL_PENDING"
        private const val PENDING_NOTIF_ID = 43
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val EXTRA_PORT = "port"
        const val EXTRA_ALLOW_UPLOADS = "allow_uploads"
        const val EXTRA_ALLOW_DELETE = "allow_delete"
        const val EXTRA_PASSWORD = "password"
        private const val NOTIF_ID = 42
        private const val TRANSFER_ID_BASE = 100
        private const val GROUP_TRANSFERS = "com.wifishare.TRANSFERS"
        private val transferIdCounter = java.util.concurrent.atomic.AtomicInteger(TRANSFER_ID_BASE)

        private val _state = MutableStateFlow<State>(State.Stopped)
        val state = _state.asStateFlow()

        private fun updateState(value: State) {
            _state.value = value
        }

        fun start(
            ctx: Context,
            folderUri: Uri,
            port: Int,
            allowUploads: Boolean,
            allowDelete: Boolean,
            password: String,
        ) {
            val intent = Intent(ctx, ServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FOLDER_URI, folderUri.toString())
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_ALLOW_UPLOADS, allowUploads)
                putExtra(EXTRA_ALLOW_DELETE, allowDelete)
                putExtra(EXTRA_PASSWORD, password)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, ServerService::class.java).setAction(ACTION_STOP)
            ctx.startService(intent)
        }
    }
}
