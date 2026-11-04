package com.pulseloop.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pulseloop.BuildConfig
import com.pulseloop.coach.tools.ToolExecutionContext
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ui.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * Runs [McpServer] while the user has it on, always behind an ongoing notification that says so
 * and stops it on a tap. Listens only on Wi-Fi, Ethernet, hotspot and Tailscale addresses, never
 * on mobile data; turns itself off after the chosen time; never comes back by itself
 * (`START_NOT_STICKY`, not started on boot). Not exported: only PulseLoop's own settings screen
 * starts it. Threat model: docs/mcp-threat-model.md.
 *
 * Foreground-service type `dataSync`: the closest type that exists for minSdk 26 / targetSdk 35.
 * Android 15 caps `dataSync` at about 6 hours a day and then calls [onTimeout], which stops it,
 * so on those phones the 8-hour choice ends at the system's limit.
 *
 * While it runs it follows the phone's networks ([watchNetworks]): when an address comes or goes
 * (Wi-Fi joined or left, a DHCP renewal, Tailscale up or down) it listens on the new set and the
 * shown addresses update, so nobody has to turn it off and on again. The set is always read from
 * [McpNet.localAddresses], which never includes mobile data.
 */
class McpService : Service() {

    data class Status(
        val running: Boolean = false,
        /** Where tools connect, e.g. `http://192.168.1.20:8765/mcp`; empty while off. */
        val urls: List<String> = emptyList(),
        /** The last start found no Wi-Fi or Tailscale address to listen on. */
        val unreachable: Boolean = false,
        val offAt: Long = 0L,
    )

    private var server: McpServer? = null
    /** Set in [onDestroy]: a start thread that loses the race must not leave a server running. */
    private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    private val autoOff = Runnable { stopSelf() }
    /** Registered while the server runs; unregistered in [onDestroy]. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Network callbacks arrive in bursts (lost, available, link properties): act once they settle. */
    private val refresh = Runnable { Thread({ refreshAddresses() }, "pulseloop-mcp-rebind").start() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = McpPrefs(this)
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        // A foreground service must be in the foreground within seconds of starting, whatever follows.
        goForeground()
        val now = System.currentTimeMillis()
        if (!prefs.isOn(now)) {
            stopSelf()
            return START_NOT_STICKY
        }
        main.removeCallbacks(autoOff)
        main.postDelayed(autoOff, (prefs.offAt - now).coerceAtLeast(0L))
        Thread({ startServer(prefs) }, "pulseloop-mcp-start").start()
        return START_NOT_STICKY // not brought back by Android: the user turns it on
    }

    @Synchronized
    private fun startServer(prefs: McpPrefs) {
        if (server != null || destroyed) return
        try {
            val db = PulseLoopDatabase.getInstance(applicationContext)
            val context = ToolExecutionContext(db = db, flags = McpCoachTools.FLAGS, coordinator = null)
            val table = McpCoachTools.table(context) { runBlocking { McpSummary.build(db) } }
            val handler = McpHandler(table, McpTokens(prefs), BuildConfig.VERSION_NAME, isOn = { prefs.isOn() })
            val s = McpServer(handler)
            val bound = s.start(McpNet.localAddresses(), McpNet.PORT)
            if (bound.isEmpty()) {
                s.stop()
                statusFlow.value = Status(unreachable = true)
                main.post { stopSelf() }
                return
            }
            server = s
            statusFlow.value = Status(running = true, urls = bound.map { McpNet.url(it) }, offAt = prefs.offAt)
            watchNetworks()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "MCP server failed to start: ${e.javaClass.simpleName}")
            main.post { stopSelf() }
        }
    }

    /**
     * Any network change, VPNs (Tailscale) and networks without internet (a home Wi-Fi with its
     * uplink down) included, so the listener can follow the phone's addresses. A hotspot the
     * phone serves isn't a network it joins and reports no change here; its address is picked up
     * on the next change or start.
     */
    private fun watchNetworks() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRefresh()
            override fun onLost(network: Network) = scheduleRefresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleRefresh()
        }
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (e: Exception) {
            // Without it the server keeps the addresses it started on, as it did before.
            android.util.Log.w(TAG, "MCP network watch unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun scheduleRefresh() {
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, REFRESH_DELAY_MS)
    }

    /** Listens on the phone's current addresses and shows them. Off a network it keeps running with none. */
    @Synchronized
    private fun refreshAddresses() {
        val s = server ?: return
        if (destroyed) return
        val bound = s.rebind(McpNet.localAddresses(), McpNet.PORT)
        val urls = bound.map { McpNet.url(it) }
        if (urls != statusFlow.value.urls) statusFlow.value = statusFlow.value.copy(running = true, urls = urls)
    }

    /** Android 15+: the daily `dataSync` allowance ran out. Stop now, or the system crashes the app. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(autoOff)
        main.removeCallbacks(refresh)
        synchronized(this) {
            destroyed = true
            networkCallback?.let { cb ->
                runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            }
            networkCallback = null
            server?.stop()
            server = null
        }
        McpPrefs(this).apply { enabled = false; offAt = 0L }
        statusFlow.value = statusFlow.value.copy(running = false, urls = emptyList(), offAt = 0L)
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("AI tools on your network"), NotificationManager.IMPORTANCE_LOW)
                .apply { description = tr("Shown while paired AI tools on your network can read your health data.") },
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = tr("Sharing your health data with paired AI tools on this network. Tap to stop.")
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(tr("AI tools on your network"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(stop)
            .addAction(0, tr("Stop"), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val TAG = "McpService"
        const val ACTION_STOP = "com.pulseloop.MCP_STOP"
        const val CHANNEL_ID = "mcp_server"
        const val NOTIFICATION_ID = 2201
        private const val REFRESH_DELAY_MS = 1_000L

        private val statusFlow = MutableStateFlow(Status())
        val status: StateFlow<Status> get() = statusFlow

        /** Turns it on for [McpPrefs.autoOffHours]. Call from the settings screen only (foreground). */
        fun start(context: Context) {
            val prefs = McpPrefs(context)
            prefs.enabled = true
            prefs.offAt = System.currentTimeMillis() + prefs.autoOffHours * 3_600_000L
            statusFlow.value = Status(offAt = prefs.offAt)
            ContextCompat.startForegroundService(context, Intent(context, McpService::class.java))
        }

        fun stop(context: Context) {
            McpPrefs(context).enabled = false
            context.stopService(Intent(context, McpService::class.java))
        }
    }
}
