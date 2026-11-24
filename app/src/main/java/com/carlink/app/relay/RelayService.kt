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
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var server: ServerSocket? = null
    @Volatile private var latest: ByteArray? = null

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
            val media = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, capture) ?: error("Capture permission unavailable")
            projection = media
            media.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            val metrics = resources.displayMetrics
            val scale = minOf(1.0, 960.0 / maxOf(metrics.widthPixels, metrics.heightPixels))
            val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
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
            display = media.createVirtualDisplay("CarLink Relay", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.surface, null, null)
            Thread({ serve(socket, token) }, "CarLink relay HTTP").start()
        } catch (error: Exception) {
            Log.e("CarLinkRelay", "Could not start relay", error)
            stopSelf()
        }
        return START_NOT_STICKY
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
