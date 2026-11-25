package com.carlink.app.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.HostIntegration
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue

class RelayService : Service() {
    private companion object {
        // Android Auto input protocol actions, from Input.TouchEvent.PointerAction.
        const val TOUCH_DOWN = 0
        const val TOUCH_UP = 1
        const val TOUCH_MOVE = 2
    }
    private val audio = ArrayBlockingQueue<ByteArray>(8)
    private var server: ServerSocket? = null
    private var worker: HandlerThread? = null
    private var reader: ImageReader? = null
    private var lastFrameAt = 0L
    @Volatile private var latest: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_NOT_STICKY
        val token = intent?.getStringExtra("token")
        if (token == null) {
            stopSelf()
            return START_NOT_STICKY
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
            Thread({ serve(socket, token) }, "CarLink relay HTTP").start()
        } catch (error: java.io.IOException) {
            Log.e("CarLinkRelay", "Could not start the headless relay", error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun prepareDecoder(width: Int, height: Int, dpi: Int) {
        if (reader != null) return
        val thread = HandlerThread("CarLink offscreen decoder").apply { start() }
        worker = thread
        // Android Auto negotiates a supported codec mode with a viewport derived from the iPad.
        // Keep the offscreen buffer at the same aspect ratio with even chroma dimensions.
        val scale = minOf(1.0, 1280.0 / maxOf(width, height))
        val captureWidth = (width * scale / 2).toInt() * 2
        val captureHeight = (height * scale / 2).toInt() * 2
        val images = ImageReader.newInstance(captureWidth, captureHeight, ImageFormat.YUV_420_888, 3)
        reader = images
        images.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt >= 40) {
                    lastFrameAt = now
                    latest = encodeFrame(image)
                }
            }
        }, Handler(thread.looper))
        HostIntegration.headlessMetrics = DisplayMetrics().apply {
            widthPixels = width
            heightPixels = height
            densityDpi = dpi
            density = dpi / 160f
        }
        HostIntegration.headlessMediaPcm = ::receiveMediaAudio
        HostIntegration.headlessSurface = images.surface
    }

    private fun encodeFrame(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        for (index in 0..2) {
            val plane = image.planes[index]
            val chroma = index != 0
            val rows = if (chroma) height / 2 else height
            val columns = if (chroma) width / 2 else width
            val base = plane.buffer.position()
            val destination = if (chroma) width * height + (2 - index) else 0
            val step = if (chroma) 2 else 1
            for (row in 0 until rows) {
                val start = base + row * plane.rowStride
                for (column in 0 until columns) {
                    nv21[destination + (row * columns + column) * step] =
                        plane.buffer.get(start + column * plane.pixelStride)
                }
            }
        }
        return ByteArrayOutputStream().use { output ->
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 78, output)
            output.toByteArray()
        }
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
        val width = HeadUnitScreenConfig.getNegotiatedWidth()
        val height = HeadUnitScreenConfig.getNegotiatedHeight()
        if (width < 1 || height < 1) return
        fun send(action: Int, index: Int) {
            connection.send(TouchEvent(SystemClock.elapsedRealtime(), action, 0,
                listOf(Triple(0, (points[index] * (width - 1)).toInt(), (points[index + 1] * (height - 1)).toInt())))
        }
        send(TOUCH_DOWN, 0)
        if (points[0] != points[2] || points[1] != points[3]) send(TOUCH_MOVE, 2)
        send(TOUCH_UP, 2)
    }

    private fun serve(server: ServerSocket, token: String) {
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
                            if (path.size == 3 && path[0] == "POST" && path[1].startsWith("/display/$token/")) {
                                val size = path[1].substringAfter("/display/$token/").split(',').map { it.toIntOrNull() ?: 0 }
                                if (size.size == 3 && size[0] in 320..4096 && size[1] in 240..3072 &&
                                    size[0].toLong() * size[1] <= 6_000_000 && size[2] in 120..480) {
                                    val setup = java.util.concurrent.FutureTask<Boolean> {
                                        if (!server.isClosed) prepareDecoder(size[0], size[1], size[2])
                                        reader?.surface?.isValid == true
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

    override fun onDestroy() {
        server?.close()
        HostIntegration.headlessSurface = null
        HostIntegration.headlessMetrics = null
        HostIntegration.headlessMediaPcm = null
        if (reader != null) App.provide(this).videoDecoder.setSurface(null)
        val images = reader
        worker?.let { thread ->
            Handler(thread.looper).post { images?.close() }
            thread.quitSafely()
        }
        super.onDestroy()
    }
}
