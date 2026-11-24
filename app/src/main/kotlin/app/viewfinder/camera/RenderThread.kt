package app.viewfinder.camera

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the EGL context, the OES camera texture and the shader. Renders only when the camera delivers a frame.
 *
 * Geometry contract (verified on device in the Phase 0 spike):
 *  - The SurfaceTexture matrix may already contain a rotation; it is read, never assumed to be zero.
 *  - [Params.relRotationDeg] is the clockwise rotation the RAW buffer needs to look upright on the display.
 *  - The picture is drawn into [Params] display rect, cropped by cropX/cropY (fractions of the image kept).
 *    Desqueeze needs no special code: the displayed aspect is already the desqueezed frame aspect.
 */
class RenderThread {
    data class Params(
        /** Display rect in surface pixels, y-down. Width 0 = nothing to draw. */
        val left: Int = 0,
        val top: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        /** Fraction of the oriented image width / height that stays visible (0..1). */
        val cropX: Float = 1f,
        val cropY: Float = 1f,
        val relRotationDeg: Int = 0,
    )

    private val thread = HandlerThread("vf-render").also { it.start() }
    private val handler = Handler(thread.looper)

    val metrics = FrameMetrics()
    @Volatile var params = Params()
    @Volatile var glRenderer: String = "?"
        private set
    @Volatile var texRotationDeg: Int = 0
        private set
    @Volatile var appliedRotationDeg: Int = 0
        private set

    private var egl: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var window: EGLSurface? = null
    private var viewW = 0
    private var viewH = 0
    private var program = 0
    private var vbo = 0
    private var texId = 0
    private var uMvp = 0
    private var uTex = 0
    private var uSampler = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)

    init {
        handler.post { initGl() }
    }

    fun prepareCameraSurface(width: Int, height: Int, onReady: (Surface) -> Unit) {
        handler.post {
            releaseCameraSurface()
            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({ onFrame() }, handler)
            surfaceTexture = st
            cameraSurface = Surface(st)
            metrics.reset(SystemClock.elapsedRealtimeNanos())
            onReady(cameraSurface!!)
        }
    }

    fun setWindow(surface: Surface) {
        handler.post {
            val e = egl ?: return@post
            destroyWindow()
            window = e.createWindowSurface(surface).also { e.makeCurrent(it) }
        }
    }

    fun setViewSize(w: Int, h: Int) {
        handler.post { viewW = w; viewH = h }
    }

    /** Blocks until the window EGLSurface is destroyed (required before surfaceDestroyed returns). */
    fun clearWindowBlocking() {
        val latch = CountDownLatch(1)
        handler.post {
            try { destroyWindow() } finally { latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun release() {
        handler.post {
            releaseCameraSurface()
            destroyWindow()
            if (program != 0) GLES30.glDeleteProgram(program)
            pbuffer?.let { egl?.destroySurface(it) }
            egl?.release()
            egl = null
        }
        thread.quitSafely()
    }

    private fun initGl() {
        val e = EglCore()
        egl = e
        pbuffer = e.createPbufferSurface(1, 1).also { e.makeCurrent(it) }
        glRenderer = "${GLES30.glGetString(GLES30.GL_RENDERER)} | ${GLES30.glGetString(GLES30.GL_VERSION)}"
        Log.i(TAG, "GL: $glRenderer")

        program = buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uTex = GLES30.glGetUniformLocation(program, "uTex")
        uSampler = GLES30.glGetUniformLocation(program, "uSampler")

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        val bb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(verts)
        bb.position(0)
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0)
        vbo = b[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, bb, GLES30.GL_STATIC_DRAW)
    }

    private fun releaseCameraSurface() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        cameraSurface?.release()
        surfaceTexture?.release()
        cameraSurface = null
        surfaceTexture = null
    }

    private fun destroyWindow() {
        val e = egl ?: return
        val w = window ?: return
        pbuffer?.let { e.makeCurrent(it) }
        e.destroySurface(w)
        window = null
    }

    private fun onFrame() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage() // always drain, even when no window is attached
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t)
            return
        }
        val ts = st.timestamp
        st.getTransformMatrix(texMatrix)
        // Where the quad's "up" lands in texture space tells how much rotation the matrix already applies.
        val upS = texMatrix[4]
        val upT = texMatrix[5]
        texRotationDeg = when {
            upT < -0.5f -> 0
            upS < -0.5f -> 90
            upT > 0.5f -> 180
            upS > 0.5f -> 270
            else -> 0
        }

        val e = egl
        val w = window
        if (e != null && w != null && viewW > 0 && viewH > 0) {
            draw()
            e.swapBuffers(w)
        }
        metrics.onFrame(ts, SystemClock.elapsedRealtimeNanos())
    }

    private fun draw() {
        val p = params
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (p.width <= 0 || p.height <= 0) return

        val extra = ((p.relRotationDeg - texRotationDeg) % 360 + 360) % 360
        appliedRotationDeg = extra

        // GL's origin is bottom-left; the display rect is y-down.
        GLES30.glViewport(p.left, viewH - (p.top + p.height), p.width, p.height)

        // Scale after rotate: the crop is expressed on screen axes.
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, 1f / p.cropX.coerceAtLeast(0.01f), 1f / p.cropY.coerceAtLeast(0.01f), 1f)
        Matrix.rotateM(mvp, 0, -extra.toFloat(), 0f, 0f, 1f)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glUniform1i(uSampler, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, v)
        GLES30.glAttachShader(prog, f)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "Program link failed: ${GLES30.glGetProgramInfoLog(prog)}" }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(s)}" }
        return s
    }

    private companion object {
        const val TAG = "VF_RENDER"

        const val VERTEX_SRC = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uTex;
out vec2 vUv;
void main() {
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
    vUv = (uTex * vec4(aUv, 0.0, 1.0)).xy;
}
"""

        const val FRAGMENT_SRC = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
uniform samplerExternalOES uSampler;
out vec4 outColor;
void main() {
    outColor = texture(uSampler, vUv);
}
"""
    }
}
