package com.ashwin.visionai

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
// WifiP2pConfig is used directly below (fixed network name/passphrase)
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.net.ServerSocket
import java.net.Socket

/**
 * Foreground service that:
 *  1. Turns the phone into the Wi-Fi Direct group owner the glasses already
 *     know how to join (same DIRECT-hs-xxxx network + "smart@123" password
 *     the manufacturer's app uses - this part is unchanged, we are not
 *     reinventing the glasses' own Wi-Fi stack).
 *  2. Runs a plain TCP server on port 8080 so the glasses' existing firmware
 *     can connect to it exactly like it connects to the manufacturer's app.
 *  3. Parses whatever the glasses send with GlassesProtocol and forwards it
 *     to whoever is listening (MainActivity) via a simple callback registry.
 *
 * Keeping this as a foreground service (not tied to one Activity) is what
 * lets the glasses stay connected while the phone is in a pocket and the
 * screen is off.
 */
class GlassesConnectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "glasses_connection"
        private const val NOTIF_ID = 1
        @Volatile var glassesIp: String? = null
            private set
        @Volatile var isConnected: Boolean = false
            private set

        @Volatile private var instance: GlassesConnectionService? = null

        /** Asks the running service for the current Wi-Fi Direct group's name/password,
         *  so the UI can show a QR code. Calls back with (null, null) if the service
         *  isn't running yet or the group isn't ready - caller should retry shortly. */
        fun requestGroupInfo(cb: (networkName: String?, passphrase: String?) -> Unit) {
            val svc = instance
            if (svc == null) { cb(null, null); return }
            svc.doRequestGroupInfo(cb)
        }

        private val listeners = mutableListOf<(GlassesProtocol.Event) -> Unit>()
        fun addListener(l: (GlassesProtocol.Event) -> Unit) = listeners.add(l)
        fun removeListener(l: (GlassesProtocol.Event) -> Unit) = listeners.remove(l)
        private fun emit(e: GlassesProtocol.Event) {
            if (e is GlassesProtocol.Event.DeviceIp) { glassesIp = e.ip; isConnected = true }
            if (e is GlassesProtocol.Event.PowerOff) isConnected = false
            listeners.toList().forEach { it(e) }
        }
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private var channel: WifiP2pManager.Channel? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Trying to connect to glasses..."))
        acquireWakeLock()
        startWifiDirectGroup()
        startSocketServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serverSocket?.close()
        serverThread?.interrupt()
        try { channel?.let { wifiP2pManager.removeGroup(it, null) } } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        isConnected = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Wi-Fi Direct: phone becomes the group owner the glasses join.
    // Uses a FIXED network name/password (same every launch) instead of the
    // random one Android would otherwise generate each time, so that once
    // the glasses have joined it once, their own firmware can auto-rejoin
    // this exact network the moment they power on nearby - no QR needed
    // again, matching the manufacturer app's "just works" reconnect. ----
    private fun startWifiDirectGroup() {
        channel = wifiP2pManager.initialize(this, mainLooper, null)
        val ch = channel ?: return
        val (ssid, pass) = Prefs.getOrCreateFixedWifiCredentials(this)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .build()
        try {
            wifiP2pManager.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    updateNotification("Network ready - scan the QR code once with your glasses")
                }
                override fun onFailure(reason: Int) {
                    updateNotification("Could not start Wi-Fi Direct (code $reason)")
                }
            })
        } catch (se: SecurityException) {
            updateNotification("Nearby Wi-Fi device permission is required")
        }
    }

    /** Current group's network name + passphrase, for showing as a QR / text to the glasses. */
    fun doRequestGroupInfo(cb: (networkName: String?, passphrase: String?) -> Unit) {
        val ch = channel
        if (ch == null) { cb(null, null); return }
        wifiP2pManager.requestGroupInfo(ch) { group ->
            cb(group?.networkName, group?.passphrase)
        }
    }

    // ---- TCP server the glasses' firmware connects to, just like the OEM app's port 8080 ----
    private fun startSocketServer() {
        serverThread = Thread {
            try {
                val server = ServerSocket(GlassesProtocol.SOCKET_PORT)
                serverSocket = server
                while (!Thread.currentThread().isInterrupted) {
                    val client = server.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                // server closed or port busy; nothing more to do here
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Mirrors the manufacturer app's ClientHandler.run() exactly: it reads
     *  raw bytes with InputStream.read(byte[4096]) and decodes whatever
     *  arrived as one UTF-8 string - it does NOT use BufferedReader.readLine(),
     *  so the messages are not newline-terminated. Using readLine() here
     *  before meant gestures could sit unprocessed waiting for a newline
     *  that never comes. A 5-second socket read timeout (matching the
     *  original app's own 5-second connection-monitor poll interval) lets
     *  us notice a dead connection within ~60 seconds of silence (same
     *  threshold the original app's ClientHandler.startConnectionMonitor
     *  uses) instead of blocking forever. */
    private fun handleClient(socket: Socket) {
        Thread {
            var lastMessageTime = System.currentTimeMillis()
            try {
                emit(GlassesProtocol.Event.DeviceIp(socket.inetAddress.hostAddress ?: return@Thread))
                updateNotification("Glasses connected")
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val buffer = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    val n = try {
                        input.read(buffer)
                    } catch (e: java.net.SocketTimeoutException) {
                        if (System.currentTimeMillis() - lastMessageTime > 60000) break else continue
                    }
                    if (n == -1) break
                    if (n == 0) continue
                    val header = String(buffer, 0, n, Charsets.UTF_8).trim()
                    if (header.isEmpty()) continue
                    lastMessageTime = System.currentTimeMillis()
                    emit(GlassesProtocol.parse(header))
                }
            } catch (_: Exception) {
            } finally {
                isConnected = false
                emit(GlassesProtocol.Event.PowerOff)
                updateNotification("Lost contact with glasses, waiting to reconnect")
                try { socket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    // ---- plumbing ----
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VisionBridge::glasses")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12h safety cap, renewed by restarting the service
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ग्लासेस कनेक्शन", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VisionBridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
