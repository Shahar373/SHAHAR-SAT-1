package com.shahar.shaharsat.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QuaternionTest {

    private fun assertQuaternionEquals(expected: Quaternion, actual: Quaternion, epsilon: Float = 1e-4f) {
        // Quaternions q and -q represent the same rotation.
        val sameSign = abs(expected.w - actual.w) < epsilon && abs(expected.x - actual.x) < epsilon &&
            abs(expected.y - actual.y) < epsilon && abs(expected.z - actual.z) < epsilon
        val oppositeSign = abs(expected.w + actual.w) < epsilon && abs(expected.x + actual.x) < epsilon &&
            abs(expected.y + actual.y) < epsilon && abs(expected.z + actual.z) < epsilon
        assertTrue("expected $expected but was $actual", sameSign || oppositeSign)
    }

    @Test
    fun `zero rotation is identity`() {
        val q = Quaternion.fromEulerZYXDegrees(0f, 0f, 0f)
        assertQuaternionEquals(Quaternion.IDENTITY, q)
    }

    @Test
    fun `pure roll of 90 degrees matches axis-angle construction`() {
        val q = Quaternion.fromEulerZYXDegrees(rollDeg = 90f, pitchDeg = 0f, yawDeg = 0f)
        val expected = Quaternion(w = 0.70710678f, x = 0.70710678f, y = 0f, z = 0f)
        assertQuaternionEquals(expected, q)
    }

    @Test
    fun `pure pitch of 90 degrees matches axis-angle construction`() {
        val q = Quaternion.fromEulerZYXDegrees(rollDeg = 0f, pitchDeg = 90f, yawDeg = 0f)
        val expected = Quaternion(w = 0.70710678f, x = 0f, y = 0.70710678f, z = 0f)
        assertQuaternionEquals(expected, q)
    }

    @Test
    fun `pure yaw of 90 degrees matches axis-angle construction`() {
        val q = Quaternion.fromEulerZYXDegrees(rollDeg = 0f, pitchDeg = 0f, yawDeg = 90f)
        val expected = Quaternion(w = 0.70710678f, x = 0f, y = 0f, z = 0.70710678f)
        assertQuaternionEquals(expected, q)
    }

    @Test
    fun `result is always a unit quaternion`() {
        val q = Quaternion.fromEulerZYXDegrees(37f, -52f, 178f)
        val norm = q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z
        assertEquals(1f, norm, 1e-4f)
    }

    @Test
    fun `slerp at t=0 returns the start quaternion`() {
        val a = Quaternion.fromEulerZYXDegrees(10f, 20f, 30f)
        val b = Quaternion.fromEulerZYXDegrees(80f, -40f, 170f)
        assertQuaternionEquals(a, Quaternion.slerp(a, b, 0f))
    }

    @Test
    fun `slerp at t=1 returns the end quaternion`() {
        val a = Quaternion.fromEulerZYXDegrees(10f, 20f, 30f)
        val b = Quaternion.fromEulerZYXDegrees(80f, -40f, 170f)
        assertQuaternionEquals(b, Quaternion.slerp(a, b, 1f))
    }

    @Test
    fun `slerp between a quaternion and itself returns itself at every t`() {
        val a = Quaternion.fromEulerZYXDegrees(45f, 15f, -60f)
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertQuaternionEquals(a, Quaternion.slerp(a, a, t))
        }
    }

    @Test
    fun `slerp across the yaw wrap does not jump discontinuously`() {
        // 179 degrees and -179 degrees are 2 degrees apart physically,
        // even though the raw Euler values are ~358 degrees apart — this
        // is exactly the wrap case independent per-angle filtering would
        // break on (see docs/BLE_PROTOCOL.md §6.1).
        val a = Quaternion.fromEulerZYXDegrees(0f, 0f, 179f)
        val b = Quaternion.fromEulerZYXDegrees(0f, 0f, -179f)
        val mid = Quaternion.slerp(a, b, 0.5f)

        // The midpoint should be near +/-180, not near 0 (which is what
        // naively lerping 179 and -179 as scalars would produce).
        val midYawRad = 2f * kotlin.math.atan2(mid.z, mid.w)
        val midYawDeg = Math.toDegrees(midYawRad.toDouble()).toFloat()
        val distanceFrom180 = minOf(abs(midYawDeg - 180f), abs(midYawDeg + 180f))
        assertTrue("expected midpoint near +/-180 degrees but was $midYawDeg", distanceFrom180 < 5f)
    }

    @Test
    fun `identity matrix diagonal is all ones`() {
        val m = Quaternion.IDENTITY.toMatrix4()
        assertEquals(1f, m[0], 1e-6f)
        assertEquals(1f, m[5], 1e-6f)
        assertEquals(1f, m[10], 1e-6f)
        assertEquals(1f, m[15], 1e-6f)
    }
}
