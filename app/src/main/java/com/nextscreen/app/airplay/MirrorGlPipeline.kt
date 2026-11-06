package com.nextscreen.app.airplay

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Persistent OpenGL ES 2.0 OES SurfaceTexture pipeline.
 * Decodes into an app-owned SurfaceTexture that outlives UI SurfaceView changes (e.g. rotation or
 * background/foreground transitions) and supports instant FIT / FILL / ZOOM repainting even when
 * the sender desktop is idle and transmitting 0 new frames.
 */
class MirrorGlPipeline {

    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    private var egl: EglCore? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var boundSurface: Surface? = null
    private var winW = 0
    private var winH = 0

    private var oesTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private val texMatrix = FloatArray(16)
    private val posBuffer: FloatBuffer = allocFloatBuffer(8)
    private var hasFrame = false

    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null
        private set

    @Volatile private var frameAvailable = false
    private var pendingDisplay: Surface? = null
    private var pendingWinW = 0
    private var pendingWinH = 0
    private var displayDirty = false
    private var redrawDirty = false

    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var scaleMode = "fit"

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        thread = Thread({ loop() }, "MirrorGlPipeline").also { it.start() }
        while (inputSurface == null && running) lock.wait()
    }

    fun setDisplaySurface(surface: Surface?, width: Int = 0, height: Int = 0) = synchronized(lock) {
        pendingDisplay = surface
        pendingWinW = width
        pendingWinH = height
        displayDirty = true
        lock.notifyAll()
    }

    fun updateDisplaySize(surface: Surface, width: Int, height: Int) = synchronized(lock) {
        if (pendingDisplay === surface || boundSurface === surface) {
            pendingWinW = width
            pendingWinH = height
            redrawDirty = true
            lock.notifyAll()
        }
    }

    fun setVideoSize(w: Int, h: Int) = synchronized(lock) {
        videoW = w
        videoH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
        redrawDirty = true
        lock.notifyAll()
    }

    fun setScaleMode(mode: String) = synchronized(lock) {
        if (scaleMode == mode) return@synchronized
        scaleMode = mode
        redrawDirty = true
        lock.notifyAll()
    }

    fun clearFrame() = synchronized(lock) {
        hasFrame = false
        redrawDirty = true
        lock.notifyAll()
    }

    fun release() {
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        try {
            egl = EglCore()
            initGl()
        } catch (e: Exception) {
            Log.e(TAG, "GL init failed", e)
            synchronized(lock) {
                running = false
                lock.notifyAll()
            }
            return
        }
        Matrix.setIdentityM(texMatrix, 0)
        synchronized(lock) {
            inputSurface = Surface(surfaceTexture)
            lock.notifyAll()
        }
        while (true) {
            var newDisplay: Surface? = null
            var displayChanged = false
            var doFrame = false
            var doRedraw = false
            var newW = 0
            var newH = 0
            synchronized(lock) {
                while (running && !frameAvailable && !displayDirty && !redrawDirty) {
                    lock.wait()
                }
                if (!running) break
                if (displayDirty) {
                    newDisplay = pendingDisplay
                    newW = pendingWinW
                    newH = pendingWinH
                    displayChanged = true
                    displayDirty = false
                }
                if (frameAvailable) {
                    frameAvailable = false
                    doFrame = true
                }
                if (redrawDirty) {
                    newW = pendingWinW
                    newH = pendingWinH
                    redrawDirty = false
                    doRedraw = true
                }
            }
            if (!running) break
            if (displayChanged) bindDisplay(newDisplay, newW, newH)
            if (doFrame) {
                consumeAndDraw()
            } else if (doRedraw && window != EGL14.EGL_NO_SURFACE) {
                val e = egl
                if (e != null) {
                    e.makeCurrent(window)
                    winW = if (newW > 0) newW else e.query(window, EGL14.EGL_WIDTH)
                    winH = if (newH > 0) newH else e.query(window, EGL14.EGL_HEIGHT)
                    renderCurrent()
                }
            }
        }
        releaseGl()
    }

    private fun bindDisplay(surface: Surface?, hintW: Int, hintH: Int) {
        val e = egl ?: return
        if (window != EGL14.EGL_NO_SURFACE) {
            e.makeCurrent()
            e.destroySurface(window)
            window = EGL14.EGL_NO_SURFACE
            boundSurface = null
        }
        if (surface == null || !surface.isValid) return
        window = try {
            e.windowSurface(surface)
        } catch (ex: Exception) {
            Log.w(TAG, "windowSurface failed", ex)
            EGL14.EGL_NO_SURFACE
        }
        if (window == EGL14.EGL_NO_SURFACE) return
        boundSurface = surface
        e.makeCurrent(window)
        winW = if (hintW > 0) hintW else e.query(window, EGL14.EGL_WIDTH)
        winH = if (hintH > 0) hintH else e.query(window, EGL14.EGL_HEIGHT)
        renderCurrent()
    }

    private fun consumeAndDraw() {
        val st = surfaceTexture ?: return
        val e = egl ?: return
        if (window == EGL14.EGL_NO_SURFACE) {
            e.makeCurrent()
            try {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
                hasFrame = true
            } catch (_: Exception) {
            }
            return
        }
        e.makeCurrent(window)
        val qW = e.query(window, EGL14.EGL_WIDTH)
        val qH = e.query(window, EGL14.EGL_HEIGHT)
        if (qW > 0 && qH > 0) {
            winW = qW
            winH = qH
        }
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            hasFrame = true
        } catch (ex: Exception) {
            Log.w(TAG, "updateTexImage failed", ex)
            return
        }
        renderCurrent()
    }

    private fun renderCurrent() {
        val e = egl ?: return
        if (window == EGL14.EGL_NO_SURFACE || winW <= 0 || winH <= 0) return
        GLES20.glViewport(0, 0, winW, winH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame) {
            updateVertices()
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuffer)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
        }
        e.swap(window)
    }

    private fun updateVertices() {
        val vw = videoW
        val vh = videoH
        var sx = 1f
        var sy = 1f
        if (vw > 0 && vh > 0 && winW > 0 && winH > 0) {
            val videoAspect = vw.toFloat() / vh.toFloat()
            val winAspect = winW.toFloat() / winH.toFloat()
            when (scaleMode) {
                "fill" -> {
                    sx = 1f
                    sy = 1f
                }
                "zoom" -> {
                    if (videoAspect > winAspect) {
                        sx = videoAspect / winAspect
                        sy = 1f
                    } else {
                        sx = 1f
                        sy = winAspect / videoAspect
                    }
                }
                else -> { // "fit"
                    if (videoAspect > winAspect) {
                        sx = 1f
                        sy = winAspect / videoAspect
                    } else {
                        sx = videoAspect / winAspect
                        sy = 1f
                    }
                }
            }
        }
        posBuffer.clear()
        posBuffer.put(-sx); posBuffer.put(-sy)
        posBuffer.put(sx); posBuffer.put(-sy)
        posBuffer.put(-sx); posBuffer.put(sy)
        posBuffer.put(sx); posBuffer.put(sy)
        posBuffer.position(0)
    }

    private fun initGl() {
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTex).also {
            if (videoW > 0 && videoH > 0) it.setDefaultBufferSize(videoW, videoH)
            it.setOnFrameAvailableListener {
                synchronized(lock) {
                    frameAvailable = true
                    lock.notifyAll()
                }
            }
        }
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAG)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(it)}")
        }

    private fun releaseGl() {
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        egl?.let {
            if (window != EGL14.EGL_NO_SURFACE) it.destroySurface(window)
            it.close()
        }
        egl = null
        window = EGL14.EGL_NO_SURFACE
        boundSurface = null
    }

    companion object {
        private const val TAG = "MirrorGlPipeline"

        private val TEX = allocFloatBuffer(8).apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }

        private fun allocFloatBuffer(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private const val VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n"

        private const val FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n"
    }
}
