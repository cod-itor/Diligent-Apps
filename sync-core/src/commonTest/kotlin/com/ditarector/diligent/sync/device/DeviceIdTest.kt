package com.ditarector.diligent.sync.device

import com.ditarector.diligent.sync.fakes.Sha256
import com.ditarector.diligent.sync.fakes.TestHashing
import com.ditarector.diligent.sync.port.Identity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceIdTest {
    private val cert = "diligent test certificate".encodeToByteArray()

    @Test
    fun testSha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("728119ce4dc6ebcf465623f8e9208a226805bb6d64170404e06fd11a0af76861", Sha256.hex(cert))
    }

    @Test
    fun deviceIdIsFirst32Base32CharactersOfCertificateSha256() {
        // Expected values from Python: base64.b32encode(hashlib.sha256(der).digest())[:32]
        assertEquals("OKARTTSNY3V46RSWEP4OSIEKEJUALO3N", Fingerprint.ofCertificate(cert, TestHashing).deviceId.value)
        assertEquals("4OYMIQUY7QOBJGX36TEJS35ZEQT24QPE", Fingerprint.ofCertificate(ByteArray(0), TestHashing).deviceId.value)
    }

    @Test
    fun deviceIdFormat() {
        val id = Fingerprint.ofCertificate(cert, TestHashing).deviceId
        assertEquals(32, id.value.length)
        assertTrue(id.value.all { it in 'A'..'Z' || it in '2'..'7' }, "base32 alphabet, no padding")
        assertEquals("OKAR-TTSN-Y3V4-6RSW-EP4O-SIEK-EJUA-LO3N", id.display)
        assertEquals(8, id.display.split('-').size)
    }

    @Test
    fun parseAcceptsDisplayFormAndLowercase() {
        val id = Fingerprint.ofCertificate(cert, TestHashing).deviceId
        assertEquals(id, DeviceId.parse(id.display))
        assertEquals(id, DeviceId.parse(id.value.lowercase()))
        assertEquals(id, DeviceId.parse("OKAR TTSN Y3V4 6RSW EP4O SIEK EJUA LO3N"))
        assertNull(DeviceId.parse("OKARTTSN"))
        assertNull(DeviceId.parse("OKARTTSNY3V46RSWEP4OSIEKEJUALO3N1"))
        assertNull(DeviceId.parse("OKARTTSNY3V46RSWEP4OSIEKEJUALO30"), "0 is not base32")
    }

    @Test
    fun fingerprintHexRoundTripsAndComparesByValue() {
        val fp = Fingerprint.ofCertificate(cert, TestHashing)
        assertEquals("728119ce4dc6ebcf465623f8e9208a226805bb6d64170404e06fd11a0af76861", fp.hex)
        assertEquals(fp, Fingerprint.parseHex(fp.hex))
        assertEquals(fp.hashCode(), Fingerprint.parseHex(fp.hex).hashCode())
        assertNull(Fingerprint.parseHex("zz"))
        assertFailsWith<IllegalArgumentException> { Fingerprint.ofDigest(ByteArray(31)) }
        fp.bytes[0] = 0
        assertEquals(0x72, fp.bytes[0].toInt(), "bytes is a copy")
    }

    @Test
    fun localDeviceComesFromIdentity() {
        val identity = object : Identity { override fun certificateDer() = cert }
        val device = LocalDevice.from(identity, TestHashing, "Toto's Phone", Platform.ANDROID)
        assertEquals("OKARTTSNY3V46RSWEP4OSIEKEJUALO3N", device.deviceId.value)
        assertNotEquals(device.deviceId, LocalDevice.from(object : Identity { override fun certificateDer() = ByteArray(1) }, TestHashing, "x", Platform.IOS).deviceId)
    }
}
