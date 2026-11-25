package com.carlink.app.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.HostIntegration
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue

class RelayService : Service() {
    companion object {
        // Android Auto input protocol actions, from Input.TouchEvent.PointerAction.
        const val START = "com.carlink.relay.START"
        const val APPROVE = "com.carlink.relay.APPROVE"
        const val QUERY = "com.carlink.relay.QUERY"
        const val STATUS = "com.carlink.relay.STATUS"
        const val TOUCH_DOWN = 0
        const val TOUCH_UP = 1
        const val TOUCH_MOVE = 2
    }
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var hotspotStatus = "Creating offline Wi-Fi…"
    private var pendingUntil = 0L
    @Volatile private var pendingId: String? = null
    @Volatile private var approvedId: String? = null

    private fun report() {
        val pending = pendingId != null && SystemClock.elapsedRealtime() < pendingUntil
        val message = when {
            pending -> "$hotspotStatus\n\nAn iPad is requesting access. Tap Approve iPad if you started the connection."
            approvedId != null -> "$hotspotStatus\n\niPad approved. Plug in the Android Auto phone."
            else -> hotspotStatus
        }
        sendBroadcast(Intent(STATUS).setPackage(packageName).putExtra("status", message).putExtra("pending", pending))
    }

    private val audio = ArrayBlockingQueue<ByteArray>(8)
    private var server: ServerSocket? = null
    private var worker: HandlerThread? = null
    private var video: RelayVideoTexture? = null
    @Volatile private var latest: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            APPROVE -> {
                if (approvedId == null && SystemClock.elapsedRealtime() < pendingUntil) {
                    approvedId = pendingId
                    pendingId = null
                }
                report()
                return START_NOT_STICKY
            }
            QUERY -> {
                if (server != null) report() else stopSelf()
                return START_NOT_STICKY
            }
            START -> if (server != null) {
                report()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("relay", "CarLink Relay", NotificationManager.IMPORTANCE_LOW))
        startForeground(8765, Notification.Builder(this, "relay")
            .setContentTitle("CarLink Android Auto relay")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .build())
        try {
            val socket = ServerSocket(8765)
            server = socket
            Thread({ serve(socket) }, "CarLink relay HTTP").start()
            startOfflineHotspot()
        } catch (error: java.io.IOException) {
            Log.e("CarLinkRelay", "Could not start the headless relay", error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startOfflineHotspot() {
        if (Build.VERSION.SDK_INT < 26) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                @Suppress("DEPRECATION")
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    if (server?.isClosed != false) {
                        reservation.close()
                        return
                    }
                    hotspot = reservation
                    val (name, password) = if (Build.VERSION.SDK_INT >= 30) {
                        val config = reservation.softApConfiguration
                        config.ssid to config.passphrase
                    } else {
                        val config = reservation.wifiConfiguration
                        config?.SSID to config?.preSharedKey
                    }
                    hotspotStatus = "Join this Wi-Fi network on the iPad.\nNetwork: $name\nPassword: ${password.orEmpty()}"
                    report()
                }

                override fun onFailed(reason: Int) {
                    hotspotStatus = "Offline Wi-Fi could not start ($reason). Turn off Mobile Hotspot and restart the relay."
                    report()
                }

                override fun onStopped() {
                    hotspot = null
                    hotspotStatus = "Offline Wi-Fi stopped. Restart the relay to create it again."
                    report()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (exception: RuntimeException) {
            Log.w("CarLinkRelay", "Offline hotspot unavailable", exception)
            hotspotStatus = "Offline Wi-Fi unavailable. Check Nearby devices permission and restart the relay."
        }
        report()
    }

    private fun prepareDecoder(width: Int, height: Int, dpi: Int) {
        if (video != null) return
        val thread = HandlerThread("CarLink offscreen decoder").apply { start() }
        worker = thread
        // Android Auto negotiates a supported codec mode with a viewport derived from the iPad.
        // Keep the offscreen buffer at the same aspect ratio with even chroma dimensions.
        val scale = minOf(1.0, 1280.0 / maxOf(width, height))
        val captureWidth = (width * scale / 2).toInt() * 2
        val captureHeight = (height * scale / 2).toInt() * 2
        val handler = Handler(thread.looper)
        val setup = java.util.concurrent.FutureTask {
            RelayVideoTexture(captureWidth, captureHeight, handler) { latest = it }
        }
        handler.post(setup)
        val target = setup.get()
        video = target
        HostIntegration.headlessMetrics = DisplayMetrics().apply {
            widthPixels = width
            heightPixels = height
            densityDpi = dpi
            density = dpi / 160f
        }
        HostIntegration.headlessMediaPcm = ::receiveMediaAudio
        HostIntegration.headlessSurface = target.surface
    }

    private fun receiveMediaAudio(source: ByteArray, offset: Int, length: Int) {
        // The media channel is signed 48 kHz stereo PCM. The iPad client accepts mono.
        val frames = length / 4
        if (frames <= 0) return
        val packet = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            val sample = offset + frame * 4
            val left = (source[sample].toInt() and 255) or (source[sample + 1].toInt() shl 8)
            val right = (source[sample + 2].toInt() and 255) or (source[sample + 3].toInt() shl 8)
            val mono = (left + right) / 2
            packet[frame * 2] = mono.toByte()
            packet[frame * 2 + 1] = (mono shr 8).toByte()
        }
        if (!audio.offer(packet)) {
            audio.poll()
            audio.offer(packet)
        }
    }

    private fun sendTouch(points: List<Float>) {
        val connection = App.provide(this).commManager
        if (connection.connectionState.value !is CommManager.ConnectionState.TransportStarted) return
        // Android Auto's touch origin is the top-left of the active region, after video margins.
        val width = HeadUnitScreenConfig.getNegotiatedWidth() - HeadUnitScreenConfig.getWidthMargin()
        val height = HeadUnitScreenConfig.getNegotiatedHeight() - HeadUnitScreenConfig.getHeightMargin()
        if (width < 1 || height < 1) return
        fun send(action: Int, index: Int) {
            val x = (points[index] * (width - 1)).toInt()
            val y = (points[index + 1] * (height - 1)).toInt()
            val event = TouchEvent(SystemClock.elapsedRealtime(), action, 0, listOf(Triple(0, x, y)))
            connection.send(event)
        }
        send(TOUCH_DOWN, 0)
        if (points[0] != points[2] || points[1] != points[3]) send(TOUCH_MOVE, 2)
        send(TOUCH_UP, 2)
    }

    private fun serve(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                server.accept().use { client ->
                    client.soTimeout = 1000
                    val input = client.getInputStream()
                    val request = StringBuilder()
                    // Bound memory and consume the complete headers before closing the socket.
                    repeat(4096) {
                        val byte = input.read()
                        if (byte == -1) return@use
                        request.append(byte.toChar())
                        if (request.endsWith("\r\n\r\n")) {
                            val path = request.lineSequence().first().split(' ')
                            if (path.size == 3 && path[0] == "POST" && path[1].startsWith("/pair/")) {
                                val id = path[1].substringAfter("/pair/")
                                val status = requestApproval(id)
                                client.getOutputStream().write(("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").toByteArray())
                                return@use
                            }
                            val token = approvedId ?: ""
                            if (token.isEmpty()) {
                                client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                return@use
                            }
                            if (path.size == 3 && path[0] == "POST" && path[1].startsWith("/display/$token/")) {
                                val size = path[1].substringAfter("/display/$token/").split(',').map { it.toIntOrNull() ?: 0 }
                                if (size.size == 3 && size[0] in 320..4096 && size[1] in 240..3072 &&
                                    size[0].toLong() * size[1] <= 6_000_000 && size[2] in 120..480) {
                                    val setup = java.util.concurrent.FutureTask<Boolean> {
                                        if (!server.isClosed) prepareDecoder(size[0], size[1], size[2])
                                        video?.surface?.isValid == true
                                    }
                                    Handler(Looper.getMainLooper()).post(setup)
                                    val ready = runCatching { setup.get(4, java.util.concurrent.TimeUnit.SECONDS) }
                                        .onFailure { Log.e("CarLinkRelay", "Display creation failed", it) }.getOrDefault(false)
                                    val status = if (ready) "202 Accepted" else "503 Service Unavailable"
                                    client.getOutputStream().write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                    return@use
                                }
                            }
                            if (path.size == 3 && path[0] == "GET" && path[1] == "/audio/$token") {
                                val packet = audio.poll()
                                val output = client.getOutputStream()
                                output.write(("HTTP/1.1 " + (if (packet == null) "204 No Content" else "200 OK") +
                                    "\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${packet?.size ?: 0}\r\n\r\n").toByteArray())
                                if (packet != null) output.write(packet)
                                return@use
                            }
                            if (path.size == 3 && path[0] == "POST" && path[1].startsWith("/touch/$token/")) {
                                val points = path[1].substringAfter("/touch/$token/").split(',')
                                    .mapNotNull { it.toFloatOrNull() }
                                if (points.size == 4 && points.all { it.isFinite() && it in 0f..1f }) {
                                    sendTouch(points)
                                    client.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                                    return@use
                                }
                            }
                            val authorized = request.lineSequence().first() in
                                listOf("GET /frame/$token HTTP/1.1", "GET /frame/$token HTTP/1.0")
                            val bytes = if (authorized) latest else null
                            val status = when {
                                !authorized -> "404 Not Found"
                                bytes == null -> "204 No Content"
                                else -> "200 OK"
                            }
                            val output = client.getOutputStream()
                            output.write(("HTTP/1.1 $status\r\nConnection: close\r\nCache-Control: no-store\r\n" +
                                "Content-Type: image/jpeg\r\nContent-Length: ${bytes?.size ?: 0}\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII))
                            if (bytes != null) output.write(bytes)
                            return@use
                        }
                    }
                }
            } catch (error: java.io.IOException) {
                if (server.isClosed) return
            }
        }
    }

    private fun requestApproval(id: String): String {
        if (id.length != 32 || id.any { it !in '0'..'9' && it !in 'a'..'f' }) return "400 Bad Request"
        val main = Handler(Looper.getMainLooper())
        val decision = java.util.concurrent.FutureTask {
            when {
                approvedId == id -> "204 No Content"
                approvedId != null -> "409 Conflict"
                pendingId == id && SystemClock.elapsedRealtime() < pendingUntil -> "202 Accepted"
                pendingId != null && SystemClock.elapsedRealtime() < pendingUntil -> "409 Conflict"
                else -> {
                    pendingId = id
                    pendingUntil = SystemClock.elapsedRealtime() + 60_000
                    report()
                    "202 Accepted"
                }
            }
        }
        main.post(decision)
        return runCatching { decision.get(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault("503 Service Unavailable")
    }

    override fun onDestroy() {
        hotspot?.close()
        hotspot = null
        server?.close()
        HostIntegration.headlessSurface = null
        HostIntegration.headlessMetrics = null
        HostIntegration.headlessMediaPcm = null
        if (video != null) App.provide(this).videoDecoder.setSurface(null)
        val texture = video
        worker?.let { thread ->
            Handler(thread.looper).post { texture?.close() }
            thread.quitSafely()
        }
        super.onDestroy()
    }
}
