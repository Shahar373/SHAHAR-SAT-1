package com.shahar.shaharsat.gl

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal unit-quaternion math for the attitude visualization. Firmware
 * ships Euler angles (roll/pitch/yaw, degrees — see docs/BLE_PROTOCOL.md
 * §6.1), which get converted to a quaternion once on receipt and then
 * SLERPed toward, rather than filtering the three angles independently.
 * That's deliberate: independent low-pass filtering breaks at yaw's
 * ±180° wrap and degenerates near pitch ±90° (gimbal lock in the
 * *representation*, not the physical sensor) — SLERP between quaternions
 * has neither problem.
 *
 * Convention (see docs/BLE_PROTOCOL.md §6.1's body-axes diagram):
 * intrinsic Z(yaw)-Y(pitch)-X(roll), which composes as
 * q = qz(yaw) ⊗ qy(pitch) ⊗ qx(roll) — roll applied first, then pitch,
 * then yaw, matching the standard aerospace yaw-pitch-roll convention.
 */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {

    operator fun times(o: Quaternion): Quaternion = Quaternion(
        w = w * o.w - x * o.x - y * o.y - z * o.z,
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w
    )

    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-9f) IDENTITY else Quaternion(w / n, x / n, y / n, z / n)
    }

    /** Column-major 4x4 rotation matrix, ready for GLES20.glUniformMatrix4fv. */
    fun toMatrix4(): FloatArray {
        val ww = w * w; val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            ww + xx - yy - zz, 2 * (xy + wz), 2 * (xz - wy), 0f,
            2 * (xy - wz), ww - xx + yy - zz, 2 * (yz + wx), 0f,
            2 * (xz + wy), 2 * (yz - wx), ww - xx - yy + zz, 0f,
            0f, 0f, 0f, 1f
        )
    }

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        private fun axisAngle(halfAngleRad: Float, ax: Float, ay: Float, az: Float) =
            Quaternion(cos(halfAngleRad), ax * sin(halfAngleRad), ay * sin(halfAngleRad), az * sin(halfAngleRad))

        /** [rollDeg]/[pitchDeg]/[yawDeg] as shipped by AttitudeFrame — see docs/TELEMETRY.md §2.1. */
        fun fromEulerZYXDegrees(rollDeg: Float, pitchDeg: Float, yawDeg: Float): Quaternion {
            val r = Math.toRadians(rollDeg.toDouble()).toFloat() / 2f
            val p = Math.toRadians(pitchDeg.toDouble()).toFloat() / 2f
            val yw = Math.toRadians(yawDeg.toDouble()).toFloat() / 2f
            val qx = axisAngle(r, 1f, 0f, 0f)
            val qy = axisAngle(p, 0f, 1f, 0f)
            val qz = axisAngle(yw, 0f, 0f, 1f)
            return (qz * qy * qx).normalized()
        }

        /** Shortest-path spherical interpolation, `t` in [0,1]. */
        fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
            var bx = b
            var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            if (dot < 0f) {
                bx = Quaternion(-b.w, -b.x, -b.y, -b.z)
                dot = -dot
            }
            dot = dot.coerceIn(-1f, 1f)

            if (dot > 0.9995f) {
                // Nearly identical — linear interpolation avoids a division by ~0 in sin(theta).
                return Quaternion(
                    a.w + t * (bx.w - a.w), a.x + t * (bx.x - a.x),
                    a.y + t * (bx.y - a.y), a.z + t * (bx.z - a.z)
                ).normalized()
            }

            val theta0 = Math.acos(dot.toDouble())
            val theta = theta0 * t
            val sinTheta0 = Math.sin(theta0)
            val sinTheta = Math.sin(theta)
            val s0 = (Math.cos(theta) - dot * sinTheta / sinTheta0).toFloat()
            val s1 = (sinTheta / sinTheta0).toFloat()

            return Quaternion(
                s0 * a.w + s1 * bx.w, s0 * a.x + s1 * bx.x,
                s0 * a.y + s1 * bx.y, s0 * a.z + s1 * bx.z
            ).normalized()
        }
    }
}
