package com.ditarector.diligent.sync.pairing

import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.device.LocalDevice
import com.ditarector.diligent.sync.device.Platform
import com.ditarector.diligent.sync.fakes.FakeClock
import com.ditarector.diligent.sync.fakes.FakeConnection
import com.ditarector.diligent.sync.fakes.FakeRandom
import com.ditarector.diligent.sync.fakes.FakeSrp
import com.ditarector.diligent.sync.fakes.TestHashing
import com.ditarector.diligent.sync.message.ErrorCodes
import com.ditarector.diligent.sync.message.ErrorMessage
import com.ditarector.diligent.sync.message.PairBind
import com.ditarector.diligent.sync.message.PairProof
import com.ditarector.diligent.sync.message.PairRequest
import com.ditarector.diligent.sync.wire.Inbound
import com.ditarector.diligent.sync.wire.Outbox
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private val clock = FakeClock()
    private val random = FakeRandom()
    private val srp = FakeSrp()
    private val laptopCert = "laptop certificate".encodeToByteArray()
    private val laptop = LocalDevice(Fingerprint.ofCertificate(laptopCert, TestHashing), "Laptop", Platform.MACOS)
    private val targetEvents = mutableListOf<TargetEvent>()
    private val target = PairingTarget(laptop, srp, TestHashing, clock, random, { targetEvents += it })

    /** One requester device connected to the target; messages flow through a queue. */
    private inner class Peer(name: String) {
        val cert = "$name certificate".encodeToByteArray()
        val device = LocalDevice(Fingerprint.ofCertificate(cert, TestHashing), name, Platform.ANDROID)
        val toTarget = FakeConnection(peerCertificateDer = laptopCert)
        val toRequester = FakeConnection(peerCertificateDer = cert)
        val targetOutbox = Outbox(toRequester)
        val states = mutableListOf<RequesterState>()
        val requester = PairingRequester(Outbox(toTarget), device, srp, TestHashing, clock, { states += it })
        val fromTarget = mutableListOf<Inbound>()
        private val queue = ArrayDeque<Pair<Boolean, Inbound>>()

        init {
            toTarget.onSent = { queue.addLast(true to it) }
            toRequester.onSent = { queue.addLast(false to it); fromTarget += it }
        }

        fun pump() {
            while (true) {
                val (forTarget, item) = queue.removeFirstOrNull() ?: return
                if (forTarget) target.handle(targetOutbox, item) else requester.handle(item)
            }
        }

        fun request(): Peer = apply { requester.start(); pump() }

        fun type(code: String) {
            assertTrue(requester.submitCode(code), "code accepted for sending")
            pump()
        }

        val lastError: ErrorMessage? get() = fromTarget.mapNotNull { (it as? Inbound.Received)?.message as? ErrorMessage }.lastOrNull()
    }

    private fun shownCode(): String = targetEvents.filterIsInstance<TargetEvent.CodeShown>().last().code.digits
    private fun wrong(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    @Test
    fun codeIsSixDigitsFromTheSecureRandomPort() {
        random.enqueue(0, 7, 0xA1, 0x20) // 0x0007A120 = 500000
        assertEquals("500000", PairingCode.generate(random).digits)
        random.enqueue(0, 0, 0, 42)
        val code = PairingCode.generate(random)
        assertEquals("000042", code.digits)
        assertEquals("000 042", code.display)
        assertFalse(code.toString().contains("42"), "never printed in logs")
    }

    @Test
    fun codeGenerationRejectsBiasedValues() {
        random.enqueue(0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 5) // 2^32-1 is above the unbiased limit, drawn again
        assertEquals("000005", PairingCode.generate(random).digits)
    }

    @Test
    fun codeParsing() {
        assertEquals("482913", PairingCode.parse("482 913")?.digits)
        assertEquals("482913", PairingCode.parse("482-913")?.digits)
        assertNull(PairingCode.parse("48291"))
        assertNull(PairingCode.parse("48291a"))
        assertNull(PairingCode.parse("4829134"))
    }

    @Test
    fun happyPathPairsBothSides() {
        val phone = Peer("Phone").request()
        val shown = assertIs<TargetEvent.CodeShown>(targetEvents.single())
        assertEquals("Phone", shown.name)
        assertEquals(phone.device.deviceId, shown.deviceId)
        assertEquals(clock.now + 120_000, shown.expiresAtMs)
        assertEquals(RequesterState.NeedCode(null), phone.requester.state)

        phone.type(shownCode())

        val requesterRecord = assertIs<RequesterState.Paired>(phone.requester.state).record
        assertEquals(laptop.deviceId, requesterRecord.deviceId)
        assertEquals(laptop.fingerprint.hex, requesterRecord.fingerprint)
        assertEquals("Laptop", requesterRecord.name)
        assertEquals(Platform.MACOS, requesterRecord.platform)

        val targetRecord = targetEvents.filterIsInstance<TargetEvent.Paired>().single().record
        assertEquals(phone.device.deviceId, targetRecord.deviceId)
        assertEquals(phone.device.fingerprint.hex, targetRecord.fingerprint)
        assertEquals(clock.now, targetRecord.pairedAt)
        assertTrue(TargetEvent.CodeHidden(HideReason.USED) in targetEvents)
        assertNull(target.pendingCode)
    }

    @Test
    fun bindMacIsOwnFingerprintThenPeers() {
        val phone = Peer("Phone").request()
        phone.type(shownCode())
        val bind = phone.toTarget.messages.filterIsInstance<PairBind>().single()
        val key = TestHashing.sha256("K|${shownCode()}|0,1,2".encodeToByteArray())
        val mac = Base64.decode(bind.mac)
        assertContentEquals(TestHashing.hmacSha512(key, phone.device.fingerprint.bytes + laptop.fingerprint.bytes), mac)
        assertTrue(PairingBinding.verify(TestHashing, key, sender = phone.device.fingerprint, receiver = laptop.fingerprint, mac = mac))
        assertFalse(PairingBinding.verify(TestHashing, key, sender = laptop.fingerprint, receiver = phone.device.fingerprint, mac = mac), "order matters")
    }

    @Test
    fun codeIsValidJustUnder120Seconds() {
        val phone = Peer("Phone").request()
        clock.advance(119_999)
        phone.type(shownCode())
        assertIs<RequesterState.Paired>(phone.requester.state)
    }

    @Test
    fun codeExpiresAt120Seconds() {
        val phone = Peer("Phone").request()
        val code = shownCode()
        clock.advance(120_000)
        assertNull(target.pendingCode)
        phone.type(code)
        assertEquals(RequesterState.Expired, phone.requester.state)
        assertEquals(ErrorCodes.EXPIRED, phone.lastError?.code)
        assertTrue(TargetEvent.CodeHidden(HideReason.EXPIRED) in targetEvents)
        assertEquals(0, target.failedAttempts, "an expired code is not a wrong guess")
    }

    @Test
    fun checkExpiryClearsTheScreenAndTellsTheRequester() {
        val phone = Peer("Phone").request()
        clock.advance(119_000)
        target.checkExpiry()
        assertFalse(TargetEvent.CodeHidden(HideReason.EXPIRED) in targetEvents)
        clock.advance(1_000)
        target.checkExpiry()
        phone.pump()
        assertTrue(TargetEvent.CodeHidden(HideReason.EXPIRED) in targetEvents)
        assertEquals(RequesterState.Expired, phone.requester.state)
    }

    @Test
    fun codeIsSingleUse() {
        val phone = Peer("Phone").request()
        val code = shownCode()
        phone.type(code)
        assertIs<RequesterState.Paired>(phone.requester.state)
        // Replaying the proof on the same connection, or proving on a new one, finds no pending code.
        val proof = phone.toTarget.messages.filterIsInstance<PairProof>().single()
        val replay = FakeConnection(peerCertificateDer = phone.cert)
        target.handle(Outbox(replay), Inbound.Received("99", proof))
        target.handle(phone.targetOutbox, Inbound.Received("100", proof))
        assertEquals(ErrorCodes.NO_PENDING, (replay.messages.single() as ErrorMessage).code)
        assertEquals(1, targetEvents.count { it is TargetEvent.Paired })
        assertEquals(ErrorCodes.NO_PENDING, phone.lastError?.code)
    }

    @Test
    fun wrongCodeCountsDownAndAllowsRetryWithFreshChallenge() {
        val phone = Peer("Phone").request()
        val code = shownCode()
        phone.type(wrong(code))
        assertEquals(RequesterState.NeedCode(4), phone.requester.state)
        assertTrue(RequesterState.WrongCode(4) in phone.states)
        assertEquals(TargetEvent.WrongCode(4), targetEvents.last())
        assertEquals(2, srp.serversCreated, "every attempt gets a new SRP server session")
        phone.type(code)
        assertIs<RequesterState.Paired>(phone.requester.state)
        assertEquals(0, target.failedAttempts, "success resets the counter")
    }

    @Test
    fun fiveWrongCodesLockForFiveMinutes() {
        val phone = Peer("Phone").request()
        val code = shownCode()
        repeat(4) { phone.type(wrong(code)) }
        assertEquals(RequesterState.NeedCode(1), phone.requester.state)
        phone.type(wrong(code))
        assertEquals(RequesterState.Locked(300_000), phone.requester.state)
        assertEquals(clock.now + 300_000, target.lockedUntil)
        assertTrue(TargetEvent.CodeHidden(HideReason.LOCKED) in targetEvents)
        assertNull(target.pendingCode)

        // The right code no longer helps, and new requests are refused while locked.
        assertFalse(phone.requester.submitCode(code))
        clock.advance(299_999)
        val other = Peer("Other").request()
        assertEquals(RequesterState.Locked(1), other.requester.state)
        assertEquals(1, targetEvents.count { it is TargetEvent.CodeShown })
    }

    @Test
    fun lockLiftsAfterFiveMinutes() {
        val phone = Peer("Phone").request()
        val code = shownCode()
        repeat(5) { phone.type(wrong(code)) }
        clock.advance(300_000)
        assertNull(target.lockedUntil)
        val again = Peer("Phone").request()
        assertEquals(RequesterState.NeedCode(null), again.requester.state)
        assertEquals(0, target.failedAttempts)
        again.type(shownCode())
        assertIs<RequesterState.Paired>(again.requester.state)
    }

    @Test
    fun failuresAreCountedAcrossRequests() {
        val first = Peer("Phone").request()
        repeat(3) { first.type(wrong(shownCode())) }
        val second = Peer("Phone").request()
        second.type(wrong(shownCode()))
        assertEquals(RequesterState.NeedCode(1), second.requester.state)
        second.type(wrong(shownCode()))
        assertIs<RequesterState.Locked>(second.requester.state)
    }

    @Test
    fun newerRequestCancelsOlderPendingCode() {
        val first = Peer("Phone").request()
        val firstCode = shownCode()
        val second = Peer("Tablet").request()
        first.pump()
        assertEquals(RequesterState.Cancelled, first.requester.state)
        assertEquals(ErrorCodes.CANCELLED, first.lastError?.code)
        assertTrue(TargetEvent.CodeHidden(HideReason.REPLACED) in targetEvents)
        assertEquals(2, targetEvents.count { it is TargetEvent.CodeShown })
        assertNotEquals(firstCode, shownCode())

        // The older connection cannot use its code any more.
        target.handle(first.targetOutbox, Inbound.Received("5", PairProof("QQ==", "QQ==")))
        assertEquals(ErrorCodes.NO_PENDING, first.lastError?.code)

        second.type(shownCode())
        assertIs<RequesterState.Paired>(second.requester.state)
    }

    @Test
    fun requestWhoseDeviceIdDoesNotMatchItsCertificateIsRefused() {
        val connection = FakeConnection(peerCertificateDer = "someone".encodeToByteArray())
        target.handle(Outbox(connection), Inbound.Received("1", PairRequest("Fake", Platform.IOS, laptop.deviceId.value)))
        assertEquals(ErrorCodes.IDENTITY_MISMATCH, (connection.messages.single() as ErrorMessage).code)
        assertTrue(targetEvents.isEmpty())
    }

    @Test
    fun requesterRefusesUnexpectedTarget() {
        val connection = FakeConnection(peerCertificateDer = "impostor".encodeToByteArray())
        val states = mutableListOf<RequesterState>()
        val requester = PairingRequester(Outbox(connection), laptop, srp, TestHashing, clock, { states += it }, expectedDeviceId = laptop.deviceId)
        requester.start()
        assertEquals(RequesterState.Failed(ErrorCodes.IDENTITY_MISMATCH), requester.state)
        assertTrue(connection.sent.isEmpty())
    }

    @Test
    fun tamperedBindIsRejected() {
        val phone = Peer("Phone").request()
        // Let the proof through, then swap the requester's bind for one with a wrong MAC.
        phone.toTarget.onSent = { item ->
            val message = (item as? Inbound.Received)?.message
            val forwarded = if (message is PairBind) Inbound.Received(item.id, message.copy(mac = "AAAA")) else item
            target.handle(phone.targetOutbox, forwarded)
        }
        assertTrue(phone.requester.submitCode(shownCode()))
        phone.pump()
        assertEquals(TargetEvent.Failed(ErrorCodes.BIND_FAILED), targetEvents.last())
        assertTrue(targetEvents.none { it is TargetEvent.Paired })
        assertEquals(RequesterState.Failed(ErrorCodes.BIND_FAILED), phone.requester.state)
    }

    @Test
    fun notAcceptingRequests() {
        target.acceptingRequests = false
        val phone = Peer("Phone").request()
        assertEquals(RequesterState.Failed(ErrorCodes.NOT_ACCEPTING), phone.requester.state)
    }

    @Test
    fun requesterCancelHidesTheCode() {
        val phone = Peer("Phone").request()
        phone.requester.cancel()
        phone.pump()
        assertTrue(TargetEvent.CodeHidden(HideReason.CANCELLED) in targetEvents)
        assertNull(target.pendingCode)
    }

    @Test
    fun nonPairingTrafficIsLeftToTheCaller() {
        val connection = FakeConnection()
        assertFalse(target.handle(Outbox(connection), Inbound.Unsupported("1", "x.y")))
        assertFalse(target.handle(Outbox(connection), Inbound.Received("2", com.ditarector.diligent.sync.message.SyncConfirm)))
    }
}
