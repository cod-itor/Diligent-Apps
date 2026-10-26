package com.ditarector.diligent.sync.pairing

import com.ditarector.diligent.sync.device.DeviceId
import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.device.LocalDevice
import com.ditarector.diligent.sync.device.PairingRecord
import com.ditarector.diligent.sync.message.ErrorCodes
import com.ditarector.diligent.sync.message.ErrorMessage
import com.ditarector.diligent.sync.message.PairBind
import com.ditarector.diligent.sync.message.PairChallenge
import com.ditarector.diligent.sync.message.PairConfirm
import com.ditarector.diligent.sync.message.PairRequest
import com.ditarector.diligent.sync.message.PairProof
import com.ditarector.diligent.sync.port.Clock
import com.ditarector.diligent.sync.port.Hashing
import com.ditarector.diligent.sync.port.Srp
import com.ditarector.diligent.sync.port.SrpClient
import com.ditarector.diligent.sync.wire.Inbound
import com.ditarector.diligent.sync.wire.Outbox

/**
 * The device where the user types the code (SPEC A.6), for one attempt on
 * one connection. The flow is: [start] sends `pair.request`; the target
 * answers `pair.challenge`; [submitCode] sends `pair.proof`; `pair.confirm`
 * proves the target knew the code; both sides exchange `pair.bind`.
 *
 * The target sends its `pair.bind` only after it verified ours, so reaching
 * [RequesterState.Paired] means both MACs verified.
 *
 * Not thread-safe: call it from one thread.
 */
public class PairingRequester(
    private val outbox: Outbox,
    private val local: LocalDevice,
    private val srp: Srp,
    private val hashing: Hashing,
    private val clock: Clock,
    private val listener: Listener,
    private val expectedDeviceId: DeviceId? = null,
) {
    /** Receives what the pairing screen shows. */
    public fun interface Listener {
        public fun onEvent(state: RequesterState)
    }

    private val target = Fingerprint.ofCertificate(outbox.connection.peerCertificateDer, hashing)
    private var challenge: PairChallenge? = null
    private var client: SrpClient? = null
    private var sessionKey: ByteArray? = null
    private var remaining: Int? = null

    /** Where the attempt stands. */
    public var state: RequesterState = RequesterState.Idle
        private set

    /** Sends `pair.request`. Fails at once if the peer is not the device the user picked. */
    public fun start() {
        check(state == RequesterState.Idle) { "already started" }
        if (expectedDeviceId != null && expectedDeviceId != target.deviceId) {
            fail(ErrorCodes.IDENTITY_MISMATCH, tellPeer = false)
            return
        }
        move(RequesterState.WaitingForChallenge)
        outbox.send(PairRequest(local.name, local.platform, local.deviceId.value))
    }

    /**
     * Uses what the user typed as the code. Returns false, changing nothing,
     * if it is not 6 digits or no challenge awaits a code.
     */
    public fun submitCode(input: String): Boolean {
        val code = PairingCode.parse(input) ?: return false
        val current = challenge
        if (state !is RequesterState.NeedCode || current == null) return false
        val salt = Base64Bytes.decode(current.salt)
        val b = Base64Bytes.decode(current.b)
        if (salt == null || b == null) {
            fail(ErrorCodes.INVALID, tellPeer = true)
            return true
        }
        val session = srp.newClient(code.digits)
        val proof = session.respond(salt, b)
        client = session
        challenge = null
        move(RequesterState.Proving)
        outbox.send(PairProof(Base64Bytes.encode(proof.a), Base64Bytes.encode(proof.m1)))
        return true
    }

    /** Gives up; the target hides its code. */
    public fun cancel() {
        if (state.isFinal) return
        outbox.send(ErrorMessage(ErrorCodes.CANCELLED, "cancelled by the requester"))
        move(RequesterState.Cancelled)
    }

    /** Handles one item from the target. Returns false for non-pairing traffic. */
    public fun handle(item: Inbound): Boolean {
        val message = (item as? Inbound.Received)?.message ?: return false
        if (state.isFinal) return message is PairChallenge || message is PairConfirm || message is PairBind || message is ErrorMessage
        when (message) {
            is PairChallenge -> onChallenge(item, message)
            is PairConfirm -> onConfirm(item, message)
            is PairBind -> onBind(item, message)
            is ErrorMessage -> onError(message)
            else -> return false
        }
        return true
    }

    private fun onChallenge(item: Inbound, message: PairChallenge) {
        if (state != RequesterState.WaitingForChallenge) {
            outbox.replyError(item, ErrorCodes.UNEXPECTED)
            return
        }
        challenge = message
        move(RequesterState.NeedCode(remaining))
    }

    private fun onConfirm(item: Inbound, message: PairConfirm) {
        val session = client
        if (state != RequesterState.Proving || session == null) {
            outbox.replyError(item, ErrorCodes.UNEXPECTED)
            return
        }
        val m2 = Base64Bytes.decode(message.m2)
        if (m2 == null || !session.verifyServer(m2)) {
            // The target could not prove it knew the code: not the device the user is looking at.
            fail(ErrorCodes.BIND_FAILED, tellPeer = true)
            return
        }
        val key = session.sessionKey()
        sessionKey = key
        move(RequesterState.Binding)
        outbox.send(
            PairBind(
                local.deviceId.value, local.name, local.platform,
                Base64Bytes.encode(PairingBinding.mac(hashing, key, own = local.fingerprint, peer = target)),
            ),
        )
    }

    private fun onBind(item: Inbound, message: PairBind) {
        val key = sessionKey
        if (state != RequesterState.Binding || key == null) {
            outbox.replyError(item, ErrorCodes.UNEXPECTED)
            return
        }
        val mac = Base64Bytes.decode(message.mac)
        val valid = DeviceId.parse(message.deviceId) == target.deviceId &&
            mac != null && PairingBinding.verify(hashing, key, sender = target, receiver = local.fingerprint, mac = mac)
        if (!valid) {
            fail(ErrorCodes.BIND_FAILED, tellPeer = true)
            return
        }
        val record = PairingRecord(
            deviceId = target.deviceId,
            name = message.name,
            platform = message.platform,
            fingerprint = target.hex,
            pairedAt = clock.nowMs(),
        )
        move(RequesterState.Paired(record))
    }

    private fun onError(error: ErrorMessage) {
        when (error.code) {
            ErrorCodes.WRONG_CODE -> {
                remaining = error.remaining
                client = null
                move(RequesterState.WrongCode(error.remaining))
                // A fresh pair.challenge follows; then the user types the code again.
                state = RequesterState.WaitingForChallenge
            }
            ErrorCodes.EXPIRED -> move(RequesterState.Expired)
            ErrorCodes.LOCKED -> move(RequesterState.Locked(error.retryAfterMs))
            ErrorCodes.CANCELLED -> move(RequesterState.Cancelled)
            else -> move(RequesterState.Failed(error.code))
        }
    }

    private fun fail(code: String, tellPeer: Boolean) {
        if (tellPeer) outbox.send(ErrorMessage(code))
        move(RequesterState.Failed(code))
    }

    private fun move(next: RequesterState) {
        state = next
        listener.onEvent(next)
    }
}

/** Where a [PairingRequester] stands; each value is also what its screen shows. */
public sealed class RequesterState {
    /** No further messages are expected. */
    public open val isFinal: Boolean get() = false

    public data object Idle : RequesterState()

    /** `pair.request` sent; the target is showing its code. */
    public data object WaitingForChallenge : RequesterState()

    /** Ask the user for the code. [remaining] is set after a wrong code. */
    public data class NeedCode(val remaining: Int?) : RequesterState()

    /** `pair.proof` sent. */
    public data object Proving : RequesterState()

    /** The code was wrong; [remaining] tries are left before the target locks. Passing state. */
    public data class WrongCode(val remaining: Int?) : RequesterState()

    /** Code verified, MACs being exchanged. */
    public data object Binding : RequesterState()

    /** Done; store [record] and pin its fingerprint. */
    public data class Paired(val record: PairingRecord) : RequesterState() {
        override val isFinal: Boolean get() = true
    }

    /** The code is older than 120 seconds; request a new one. */
    public data object Expired : RequesterState() {
        override val isFinal: Boolean get() = true
    }

    /** Too many wrong codes; the target accepts requests again after [retryAfterMs]. */
    public data class Locked(val retryAfterMs: Long?) : RequesterState() {
        override val isFinal: Boolean get() = true
    }

    /** Cancelled here or on the target, or replaced by a newer request. */
    public data object Cancelled : RequesterState() {
        override val isFinal: Boolean get() = true
    }

    /** Failed with an [ErrorCodes] value. */
    public data class Failed(val code: String) : RequesterState() {
        override val isFinal: Boolean get() = true
    }
}
