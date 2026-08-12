package com.shahar.shaharsat.gl

/**
 * Static geometry for the attitude visualization: a 1U body cube plus
 * two solar-panel wings, in the body-axis convention documented in
 * docs/BLE_PROTOCOL.md §6.1 (+X front/ph4, -X back/ph2, +Y right/ph3,
 * -Y left/ph1, +Z top). Deliberately simple flat-shaded triangles, no
 * texturing or lighting model — see docs/ARCHITECTURE.md's "no heavy
 * engine" note; this is a hand-rolled OpenGL ES 2.0 mesh, not a 3D
 * engine.
 *
 * Vertex layout: position.xyz, color.rgba (7 floats/vertex), GL_TRIANGLES.
 */
object CubeSatMesh {
    private const val H = 0.5f // half-extent of the body cube

    // Per-face colors so orientation reads at a glance.
    private val FRONT = floatArrayOf(0.10f, 0.85f, 0.55f, 1f)  // +X — matches accent green
    private val BACK = floatArrayOf(0.06f, 0.45f, 0.32f, 1f)   // -X
    private val RIGHT = floatArrayOf(0.24f, 0.65f, 0.96f, 1f)  // +Y — matches accent blue
    private val LEFT = floatArrayOf(0.14f, 0.35f, 0.55f, 1f)   // -Y
    private val TOP = floatArrayOf(0.96f, 0.65f, 0.14f, 1f)    // +Z — matches accent amber
    private val BOTTOM = floatArrayOf(0.35f, 0.28f, 0.14f, 1f) // -Z
    private val PANEL = floatArrayOf(0.20f, 0.30f, 0.55f, 0.95f)

    val body: FloatArray = buildList {
        addFace(this, floatArrayOf(H, -H, -H), floatArrayOf(H, H, -H), floatArrayOf(H, H, H), floatArrayOf(H, -H, H), FRONT)
        addFace(this, floatArrayOf(-H, H, -H), floatArrayOf(-H, -H, -H), floatArrayOf(-H, -H, H), floatArrayOf(-H, H, H), BACK)
        addFace(this, floatArrayOf(H, H, -H), floatArrayOf(-H, H, -H), floatArrayOf(-H, H, H), floatArrayOf(H, H, H), RIGHT)
        addFace(this, floatArrayOf(-H, -H, -H), floatArrayOf(H, -H, -H), floatArrayOf(H, -H, H), floatArrayOf(-H, -H, H), LEFT)
        addFace(this, floatArrayOf(-H, -H, H), floatArrayOf(H, -H, H), floatArrayOf(H, H, H), floatArrayOf(-H, H, H), TOP)
        addFace(this, floatArrayOf(-H, H, -H), floatArrayOf(H, H, -H), floatArrayOf(H, -H, -H), floatArrayOf(-H, -H, -H), BOTTOM)
    }.toFloatArray()

    /** Two wings extending outward from the +Y/-Y faces (right/left), representing deployed panels. */
    val panels: FloatArray = buildList {
        val pz = 0.06f
        addFace(this, floatArrayOf(-0.4f, H, -pz), floatArrayOf(0.4f, H, -pz), floatArrayOf(0.4f, 1.6f, -pz), floatArrayOf(-0.4f, 1.6f, -pz), PANEL)
        addFace(this, floatArrayOf(-0.4f, -1.6f, -pz), floatArrayOf(0.4f, -1.6f, -pz), floatArrayOf(0.4f, -H, -pz), floatArrayOf(-0.4f, -H, -pz), PANEL)
    }.toFloatArray()

    val bodyVertexCount = body.size / 7
    val panelVertexCount = panels.size / 7

    private fun addFace(out: MutableList<Float>, a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, color: FloatArray) {
        // Two triangles, a-b-c and a-c-d, all sharing the same flat color.
        for (v in listOf(a, b, c, a, c, d)) {
            out.add(v[0]); out.add(v[1]); out.add(v[2])
            out.add(color[0]); out.add(color[1]); out.add(color[2]); out.add(color[3])
        }
    }
}
