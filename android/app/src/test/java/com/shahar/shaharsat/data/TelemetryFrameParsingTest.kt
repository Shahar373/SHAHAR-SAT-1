package com.shahar.shaharsat.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These mirror the exact frame shapes in docs/BLE_PROTOCOL.md §6 and the
 * error-handling table in §9 — a malformed frame must never crash the
 * app, and an omitted field must decode to null, not 0.
 */
class TelemetryFrameParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `full attitude frame decodes all fields`() {
        val frame = json.decodeFromString<AttitudeFrame>(
            """{"v":1,"t":128374,"r":12.24,"p":-3.41,"y":182.07,"cal":1}"""
        )
        assertEquals(1, frame.v)
        assertEquals(128374L, frame.t)
        assertEquals(12.24f, frame.r!!, 0.001f)
        assertEquals(-3.41f, frame.p!!, 0.001f)
        assertEquals(182.07f, frame.y!!, 0.001f)
        assertTrue(frame.isCalibrated)
    }

    @Test
    fun `uncalibrated attitude frame omits angles rather than sending zero`() {
        val frame = json.decodeFromString<AttitudeFrame>("""{"v":1,"t":128374,"cal":0}""")
        assertNull(frame.r)
        assertNull(frame.p)
        assertNull(frame.y)
        assertTrue(!frame.isCalibrated)
    }

    @Test
    fun `power frame decodes EPS and sun sensor fields together`() {
        val frame = json.decodeFromString<PowerFrame>(
            """{"v":1,"t":1,"bv":3.91,"bi":-42.5,"sv":4.82,"sil":31.2,"sir":28.7,"sp":289.4,
                |"ph1":812,"ph2":140,"ph3":95,"ph4":1203}""".trimMargin()
        )
        assertEquals(3.91f, frame.bv!!, 0.001f)
        assertEquals(-42.5f, frame.bi!!, 0.001f)
        assertEquals(1203f, frame.ph4!!, 0.001f)
    }

    @Test
    fun `power frame with only sun sensors omits EPS fields`() {
        val frame = json.decodeFromString<PowerFrame>("""{"v":1,"t":1,"ph1":10,"ph2":20,"ph3":30,"ph4":40}""")
        assertNull(frame.bv)
        assertEquals(10f, frame.ph1!!, 0.001f)
    }

    @Test
    fun `system frame decodes nested hardware presence object`() {
        val frame = json.decodeFromString<SystemFrame>(
            """{"v":1,"t":1,"cs":"MYSAT","fw":"v.1.4","up":128,"mode":"DESKTOP","mot":1,"led":0,
                |"log":0,"heap":94512,"rtc":"2026-08-11 18:55:02",
                |"hw":{"bme":1,"mpu":1,"ads":1,"ina":1,"rtc":1,"cam":1},"fc":2841}""".trimMargin()
        )
        assertEquals("DESKTOP", frame.mode)
        assertTrue(frame.panelsDeployed)
        assertTrue(!frame.ledOn)
        assertEquals(1, frame.hw!!.mpu)
    }

    @Test
    fun `unknown keys are ignored rather than failing to parse`() {
        val frame = json.decodeFromString<SystemFrame>("""{"v":1,"t":1,"cs":"MYSAT","future_field":"ignored"}""")
        assertEquals("MYSAT", frame.cs)
    }

    @Test(expected = SerializationException::class)
    fun `malformed json throws rather than silently returning garbage`() {
        json.decodeFromString<AttitudeFrame>("""{"v":1,"t":128374,"r":12.24,""")
    }

    @Test(expected = SerializationException::class)
    fun `empty payload throws`() {
        json.decodeFromString<AttitudeFrame>("")
    }

    @Test
    fun `command response decodes success and failure shapes`() {
        val ok = json.decodeFromString<CommandResponse>("""{"seq":42,"cmd":"DEPLOY_SOLAR","ok":true,"msg":"Deploying solar panels"}""")
        assertTrue(ok.ok)
        assertNull(ok.err)

        val failed = json.decodeFromString<CommandResponse>(
            """{"seq":43,"cmd":"DEPLOY_SOLAR","ok":false,"err":"ERR_RATE_LIMITED","msg":"Too soon since last panel move"}"""
        )
        assertTrue(!failed.ok)
        assertEquals("ERR_RATE_LIMITED", failed.err)
    }

    @Test
    fun `encodeCommand produces the documented wire shape`() {
        val bytes = encodeCommand(Command.PING, seq = 7)
        val text = bytes.decodeToString()
        assertTrue(text.contains("\"cmd\":\"PING\""))
        assertTrue(text.contains("\"seq\":7"))
    }
}
