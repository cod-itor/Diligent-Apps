package com.ditarector.diligent.sync.device

import com.ditarector.diligent.sync.fakes.TestHashing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceRegistryTest {
    private fun fp(seed: String) = Fingerprint.ofCertificate(seed.encodeToByteArray(), TestHashing)
    private fun record(fp: Fingerprint, name: String = "Laptop") =
        PairingRecord(fp.deviceId, name, Platform.MACOS, fp.hex, pairedAt = 1000)

    private val laptop = fp("laptop cert")
    private val tablet = fp("tablet cert")

    @Test
    fun addGetAndPinCheck() {
        val registry = DeviceRegistry()
        registry.add(record(laptop))
        assertEquals("Laptop", registry[laptop.deviceId]?.name)
        assertTrue(registry.isPinned(laptop))
        assertFalse(registry.isPinned(tablet))
        assertIs<PeerCheck.Paired>(registry.check(laptop, laptop.deviceId))
        assertIs<PeerCheck.Paired>(registry.check(laptop))
    }

    @Test
    fun unknownPeerIsUnpaired() {
        val registry = DeviceRegistry(listOf(record(laptop)))
        assertIs<PeerCheck.Unpaired>(registry.check(tablet))
        assertIs<PeerCheck.Unpaired>(registry.check(tablet, tablet.deviceId))
    }

    @Test
    fun knownIdWithDifferentCertificateIsIdentityChange() {
        val registry = DeviceRegistry(listOf(record(laptop)))
        val reinstalled = fp("laptop cert after reinstall")
        val check = assertIs<PeerCheck.IdentityChanged>(registry.check(reinstalled, claimed = laptop.deviceId))
        assertEquals(laptop.deviceId, check.record.deviceId)
        assertEquals(reinstalled, check.presented)
        assertFalse(registry.isPinned(reinstalled))
    }

    @Test
    fun forgetRemovesAndReturnsTheRecord() {
        val registry = DeviceRegistry(listOf(record(laptop), record(tablet, "Tab")))
        assertEquals("Laptop", registry.forget(laptop.deviceId)?.name)
        assertNull(registry[laptop.deviceId])
        assertFalse(registry.isPinned(laptop))
        assertNull(registry.forget(laptop.deviceId))
        assertEquals(listOf("Tab"), registry.records.map { it.name })
    }

    @Test
    fun addReplacesOnRepair() {
        val registry = DeviceRegistry(listOf(record(laptop)))
        registry.add(record(laptop, "Renamed"))
        assertEquals(1, registry.records.size)
        assertEquals("Renamed", registry[laptop.deviceId]?.name)
    }

    @Test
    fun addRefusesRecordWhoseFingerprintDoesNotMatchItsId() {
        assertFailsWith<IllegalArgumentException> { DeviceRegistry().add(record(laptop).copy(deviceId = tablet.deviceId)) }
    }

    @Test
    fun updateChangesSettingsButNotIdentity() {
        val registry = DeviceRegistry(listOf(record(laptop)))
        registry.update(laptop.deviceId) { it.copy(approval = Approval.ALWAYS, lastSync = 5) }
        assertEquals(Approval.ALWAYS, registry[laptop.deviceId]?.approval)
        assertFailsWith<IllegalArgumentException> { registry.update(laptop.deviceId) { it.copy(fingerprint = tablet.hex) } }
        assertNull(registry.update(tablet.deviceId) { it })
    }

    @Test
    fun jsonRoundTrip() {
        val registry = DeviceRegistry(listOf(record(laptop).copy(lastAddresses = LastAddresses(lan = "192.168.1.5:47321")), record(tablet)))
        val text = registry.toJson()
        assertTrue("\"approval\":\"ask\"" in text)
        assertTrue("\"platform\":\"macos\"" in text)
        assertEquals(registry.records, DeviceRegistry.fromJson(text).records)
    }
}
