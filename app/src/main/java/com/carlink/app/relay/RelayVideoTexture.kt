package com.carlink.app.relay

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** All GL work, including startup and teardown, runs on the decoder worker. */
internal class RelayVideoTexture(
    private val width: Int,
    private val height: Int,
    handler: Handler,
    onFrame: (ByteArray) -> Unit,
) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var buffer = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var output: Surface? = null
    val surface: Surface get() = checkNotNull(output)

    private val attributes = mutableListOf<java.nio.FloatBuffer>()

    init {
        try {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val config = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
            ), 0, config, 0, 1, count, 0) && count[0] == 1)
            val selected = checkNotNull(config[0])
            context = EGL14.eglCreateContext(display, selected, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            buffer = EGL14.eglCreatePbufferSurface(display, selected, intArrayOf(
                EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT && buffer != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, buffer, buffer, context))
            val textureId = IntArray(1)
            GLES20.glGenTextures(1, textureId, 0)
            val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            GLES20.glBindTexture(target, textureId[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val source = SurfaceTexture(textureId[0]).also {
                texture = it
                it.setDefaultBufferSize(width, height)
            }
            output = Surface(source)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 pos; attribute vec2 uv; uniform mat4 transform; varying vec2 coords;" +
                    "void main(){ gl_Position=vec4(pos,0.0,1.0); coords=(transform*vec4(uv,0.0,1.0)).xy; }"))
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;" +
                    "uniform samplerExternalOES video; varying vec2 coords;" +
                    "void main(){ gl_FragColor=texture2D(video,coords); }"))
            GLES20.glLinkProgram(program)
            checkStatus(program, GLES20.GL_LINK_STATUS, GLES20::glGetProgramiv)
            GLES20.glUseProgram(program)
            GLES20.glViewport(0, 0, width, height)
            attribute(program, "pos", floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
            // glReadPixels begins at the bottom; put the top video row there for the bitmap.
            attribute(program, "uv", floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f))
            val matrix = FloatArray(16)
            val transform = GLES20.glGetUniformLocation(program, "transform")
            val pixels = ByteBuffer.allocateDirect(width * height * 4)
            var lastFrame = 0L
            source.setOnFrameAvailableListener({
                try {
                    source.updateTexImage()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrame >= 40) {
                        lastFrame = now
                        source.getTransformMatrix(matrix)
                        GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0)
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                        pixels.rewind()
                        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                        pixels.rewind()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            val jpeg = ByteArrayOutputStream()
                            bitmap.copyPixelsFromBuffer(pixels)
                            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 78, jpeg)) {
                                onFrame(jpeg.toByteArray())
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } catch (ex: RuntimeException) {
                    Log.e("CarLinkRelay", "Offscreen frame conversion failed", ex)
                }
            }, handler)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        checkStatus(shader, GLES20.GL_COMPILE_STATUS, GLES20::glGetShaderiv)
        return shader
    }

    private fun checkStatus(id: Int, flag: Int, read: (Int, Int, IntArray, Int) -> Unit) {
        val status = IntArray(1)
        read(id, flag, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Offscreen video GL operation failed ($flag)" }
    }

    private fun attribute(program: Int, name: String, values: FloatArray) {
        val index = GLES20.glGetAttribLocation(program, name)
        val data = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(values).position(0)
        GLES20.glVertexAttribPointer(index, 2, GLES20.GL_FLOAT, false, 0, data)
        GLES20.glEnableVertexAttribArray(index)
        attributes.add(data)
    }

    override fun close() {
        texture?.setOnFrameAvailableListener(null)
        output?.release()
        texture?.release()
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (buffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, buffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        // EGLDisplay is shared with other engine users in the process.
        EGL14.eglReleaseThread()
    }
}
