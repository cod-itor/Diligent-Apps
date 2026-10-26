package com.ditarector.diligent.sync.device

import com.ditarector.diligent.sync.DiligentLink
import com.ditarector.diligent.sync.fakes.TestHashing
import com.ditarector.diligent.sync.port.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceInfoTest {
    private val id = Fingerprint.ofCertificate("cert".encodeToByteArray(), TestHashing).deviceId

    @Test
    fun txtRecordRoundTrip() {
        val info = ServiceInfo("Toto's Laptop", Platform.WINDOWS, id, acceptsPairing = true)
        val txt = info.toTxt()
        assertEquals(mapOf("v" to "1", "name" to "Toto's Laptop", "platform" to "windows", "id" to id.value, "pair" to "1"), txt)
        assertEquals(info, ServiceInfo.fromTxt(txt))
        assertEquals(false, ServiceInfo.fromTxt(txt + ("pair" to "0"))?.acceptsPairing)
    }

    @Test
    fun txtRecordRejectsMissingOrForeignValues() {
        val good = ServiceInfo("A", Platform.LINUX, id, false).toTxt()
        assertNull(ServiceInfo.fromTxt(good + ("v" to "2")))
        assertNull(ServiceInfo.fromTxt(good - "name"))
        assertNull(ServiceInfo.fromTxt(good + ("platform" to "beos")))
        assertNull(ServiceInfo.fromTxt(good + ("id" to "short")))
    }

    @Test
    fun qrCodeRoundTrip() {
        val address = ManualAddress(Endpoint("192.168.1.20", 47321), id)
        assertEquals("diligent://192.168.1.20:47321/${id.value}", address.toUri())
        assertEquals(address, ManualAddress.parse(address.toUri()))
        assertEquals(address, ManualAddress.parse("diligent://192.168.1.20:47321/${id.display}"))
    }

    @Test
    fun qrCodeWithIpv6AndDefaultPort() {
        val v6 = ManualAddress.parse("diligent://[fd7a:115c::1]:5000/${id.value}")
        assertEquals(Endpoint("fd7a:115c::1", 5000), v6?.endpoint)
        assertEquals("diligent://[fd7a:115c::1]:5000/${id.value}", v6?.toUri())
        assertEquals(DiligentLink.DEFAULT_PORT, ManualAddress.parse("diligent://100.64.0.7/${id.value}")?.endpoint?.port)
    }

    @Test
    fun badAddressesAreRejected() {
        assertNull(ManualAddress.parse("https://1.2.3.4:1/${id.value}"))
        assertNull(ManualAddress.parse("diligent://1.2.3.4:99999/${id.value}"))
        assertNull(ManualAddress.parse("diligent://1.2.3.4:80/notanid"))
        assertNull(ManualAddress.parse("diligent://:80/${id.value}"))
        assertNull(ManualAddress.parseEndpoint("fe80::1"), "bare IPv6 needs brackets")
        assertEquals(Endpoint("laptop.local", 47321), ManualAddress.parseEndpoint("laptop.local"))
    }
}
