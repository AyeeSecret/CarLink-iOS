package com.carlink.app.relay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.ServerSocket

class RelayService : Service() {
    companion object {
        @Volatile var activeCode: String? = null
            private set
    }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var server: ServerSocket? = null
    @Volatile private var latest: ByteArray? = null
    private val audio = java.util.concurrent.ArrayBlockingQueue<ByteArray>(4)
    @Volatile private var capturingAudio = false

    @android.annotation.TargetApi(29)
    @android.annotation.SuppressLint("MissingPermission")
    private fun startAudio(media: MediaProjection) {
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(media)
            .addMatchingUid(applicationInfo.uid).build()
        val format = android.media.AudioFormat.Builder()
            .setSampleRate(48000).setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build()
        val recorder = android.media.AudioRecord.Builder().setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config).build()
        capturingAudio = true
        Thread({
            try {
                recorder.startRecording()
                while (capturingAudio) {
                    val chunk = ByteArray(7680)
                    val count = recorder.read(chunk, 0, chunk.size)
                    if (count <= 0) break
                    val packet = if (count == chunk.size) chunk else chunk.copyOf(count)
                    if (!audio.offer(packet)) {
                        audio.poll()
                        audio.offer(packet)
                    }
                }
            } catch (ex: RuntimeException) {
                Log.w("CarLinkRelay", "Playback capture stopped", ex)
            } finally {
                recorder.release()
            }
        }, "CarLink audio capture").start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val capture = intent?.getParcelableExtra<Intent>("capture")
        val token = intent?.getStringExtra("token")
        if (capture == null || token == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("relay", "CarLink Relay", NotificationManager.IMPORTANCE_LOW))
        startForeground(8765, Notification.Builder(this, "relay")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("CarLink is sharing the screen")
            .setOngoing(true)
            .build())
        try {
            val socket = ServerSocket(8765)
            server = socket
            activeCode = token
            val media = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, capture) ?: error("Capture permission unavailable")
            projection = media
            if (android.os.Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching { startAudio(media) }.onFailure { Log.w("CarLinkRelay", "Playback capture unavailable", it) }
            }
            media.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            Thread({ serve(socket, token) }, "CarLink relay HTTP").start()
        } catch (error: Exception) {
            Log.e("CarLinkRelay", "Could not start relay", error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createDisplay(width: Int, height: Int, dpi: Int) {
            if (display != null) return
            val thread = HandlerThread("CarLink capture").also { it.start() }
            worker = thread
            val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = images
            var lastFrame = 0L
            images.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastFrame < 100) return@use
                    lastFrame = now
                    val plane = image.planes[0]
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                    try {
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val frame = Bitmap.createBitmap(padded, 0, 0, width, height)
                        try {
                            latest = ByteArrayOutputStream().use {
                                frame.compress(Bitmap.CompressFormat.JPEG, 75, it)
                                it.toByteArray()
                            }
                        } finally {
                            if (frame !== padded) frame.recycle()
                        }
                    } finally {
                        padded.recycle()
                    }
                }
            }, Handler(thread.looper))
            display = getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "CarLink Relay", width, height, dpi, images.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
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
                                        if (!server.isClosed) createDisplay(size[0], size[1], size[2])
                                        display?.display?.isValid == true
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
                                    sendBroadcast(Intent(com.carlink.app.switcher.ProjectionVisibility.RELAY_TOUCH)
                                        .setPackage(packageName).putExtra("points", points.toFloatArray()))
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
        activeCode = null
        capturingAudio = false
        server?.close()
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        val images = reader
        worker?.let {
            Handler(it.looper).post { images?.close() }
            it.quitSafely()
        }
        projection?.stop()
        super.onDestroy()
    }
}
