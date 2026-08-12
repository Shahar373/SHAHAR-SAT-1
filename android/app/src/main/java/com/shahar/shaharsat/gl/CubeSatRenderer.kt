package com.shahar.shaharsat.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val VERTEX_SHADER = """
    uniform mat4 uMvp;
    attribute vec3 aPosition;
    attribute vec4 aColor;
    varying vec4 vColor;
    void main() {
        gl_Position = uMvp * vec4(aPosition, 1.0);
        vColor = aColor;
    }
"""

private const val FRAGMENT_SHADER = """
    precision mediump float;
    varying vec4 vColor;
    void main() {
        gl_FragColor = vColor;
    }
"""

/**
 * Hand-rolled OpenGL ES 2.0 renderer for the CubeSat body + panel wings
 * (CubeSatMesh). [rotation] is read once per frame — set it from the
 * SLERP-smoothed quaternion in ui/attitude/AttitudeViewModel. Deliberately
 * no lighting/texturing/engine dependency, see docs/ARCHITECTURE.md.
 */
class CubeSatRenderer : GLSurfaceView.Renderer {

    /** Set from AttitudeViewModel whenever a new AttitudeFrame arrives (~10 Hz from BLE). */
    @Volatile
    var targetRotation: Quaternion = Quaternion.IDENTITY

    // Smoothed toward targetRotation once per drawn frame (~60 Hz), so
    // motion reads smoothly between the much sparser BLE updates —
    // exponential smoothing rather than a fixed-duration tween, since
    // frames arrive at an irregular rate (docs/BLE_PROTOCOL.md §4 notes
    // the firmware's ~100ms IMU busy-wait causes real notification jitter).
    private var displayedRotation: Quaternion = Quaternion.IDENTITY
    private val smoothingFactor = 0.18f

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0

    private lateinit var bodyBuffer: FloatBuffer
    private lateinit var panelBuffer: FloatBuffer

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(
            0x0B / 255f, 0x0F / 255f, 0x14 / 255f, 1f // matches MissionColors.Background
        )
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = buildProgram()
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")

        bodyBuffer = CubeSatMesh.body.toFloatBuffer()
        panelBuffer = CubeSatMesh.panels.toFloatBuffer()

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0.6f, 3.2f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 0.5f, 20f)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        displayedRotation = Quaternion.slerp(displayedRotation, targetRotation, smoothingFactor)
        System.arraycopy(displayedRotation.toMatrix4(), 0, modelMatrix, 0, 16)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        drawMesh(bodyBuffer, CubeSatMesh.bodyVertexCount)
        drawMesh(panelBuffer, CubeSatMesh.panelVertexCount)
    }

    private fun drawMesh(buffer: FloatBuffer, vertexCount: Int) {
        val stride = 7 * 4
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        buffer.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(colorHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun buildProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}

private fun FloatArray.toFloatBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(this@toFloatBuffer)
        position(0)
    }
