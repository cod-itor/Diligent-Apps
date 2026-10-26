package com.ditarector.diligent.sync.pairing

import com.ditarector.diligent.sync.device.DeviceId
import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.device.LocalDevice
import com.ditarector.diligent.sync.device.PairingRecord
import com.ditarector.diligent.sync.device.Platform
import com.ditarector.diligent.sync.message.ErrorCodes
import com.ditarector.diligent.sync.message.ErrorMessage
import com.ditarector.diligent.sync.message.PairBind
import com.ditarector.diligent.sync.message.PairChallenge
import com.ditarector.diligent.sync.message.PairConfirm
import com.ditarector.diligent.sync.message.PairProof
import com.ditarector.diligent.sync.message.PairRequest
import com.ditarector.diligent.sync.port.Clock
import com.ditarector.diligent.sync.port.Hashing
import com.ditarector.diligent.sync.port.SecureRandomSource
import com.ditarector.diligent.sync.port.Srp
import com.ditarector.diligent.sync.port.SrpServer
import com.ditarector.diligent.sync.wire.Inbound
import com.ditarector.diligent.sync.wire.Outbox

/**
 * The device that shows the code (SPEC A.6). One instance lives as long as
 * the app, because the limits span connections:
 *
 * - at most one code is pending; a new request cancels the older one;
 * - a code is valid for [codeValidityMs] (120 s) and pairs at most once;
 * - [maxFailures] (5) wrong proofs, counted across requests so a new request
 *   cannot reset them, lock pairing for [lockoutMs] (5 minutes).
 *
 * After a wrong code the requester gets `error` `wrong_code` with the tries
 * left and a fresh `pair.challenge`, so the user can retype the same code
 * without a new request. Every SRP exchange uses a new server session.
 *
 * Not thread-safe: call [handle], [cancel], [checkExpiry] and
 * [onDisconnected] from one thread.
 */
public class PairingTarget(
    private val local: LocalDevice,
    private val srp: Srp,
    private val hashing: Hashing,
    private val clock: Clock,
    private val random: SecureRandomSource,
    private val listener: Listener,
    public val codeValidityMs: Long = CODE_VALIDITY_MS,
    public val maxFailures: Int = MAX_FAILURES,
    public val lockoutMs: Long = LOCKOUT_MS,
) {
    /** Receives what the pairing screen shows. Called synchronously from the methods above. */
    public fun interface Listener {
        public fun onEvent(event: TargetEvent)
    }

    /** Whether requests are accepted at all (the TXT record's `pair=1`). */
    public var acceptingRequests: Boolean = true

    private var pending: Pending? = null
    private var failures = 0
    private var lockedUntilMs: Long? = null

    /** The code on screen, or null when none is pending or it has expired. */
    public val pendingCode: PairingCode?
        get() = pending?.takeIf { it.stage == Stage.CHALLENGED && !isExpired(it) }?.code

    /** When pairing unlocks, or null when it is not locked. */
    public val lockedUntil: Long?
        get() = lockedUntilMs?.takeIf { clock.nowMs() < it }

    /** Wrong proofs counted towards the lock. */
    public val failedAttempts: Int get() = failures

    /**
     * Handles one item from a connection whose [outbox] answers it. Returns
     * false for items that are not pairing traffic, which the caller routes
     * elsewhere.
     */
    public fun handle(outbox: Outbox, item: Inbound): Boolean {
        val message = (item as? Inbound.Received)?.message ?: return false
        when (message) {
            is PairRequest -> onRequest(outbox, item, message)
            is PairProof -> onProof(outbox, item, message)
            is PairBind -> onBind(outbox, item, message)
            is ErrorMessage -> onError(outbox)
            else -> return false
        }
        return true
    }

    /** The user dismissed the code. */
    public fun cancel() {
        val current = pending ?: return
        pending = null
        current.outbox.send(ErrorMessage(ErrorCodes.CANCELLED, "dismissed on the target"))
        listener.onEvent(TargetEvent.CodeHidden(HideReason.CANCELLED))
    }

    /** Ends a code that ran out of time; apps call this from a timer so the screen clears on time. */
    public fun checkExpiry() {
        val current = pending ?: return
        if (current.stage == Stage.CHALLENGED && isExpired(current)) expire(current)
    }

    /** A connection closed; a code pending on it is abandoned. */
    public fun onDisconnected(outbox: Outbox) {
        if (pending?.outbox === outbox) {
            pending = null
            listener.onEvent(TargetEvent.CodeHidden(HideReason.CANCELLED))
        }
    }

    private fun onRequest(outbox: Outbox, item: Inbound, request: PairRequest) {
        if (!acceptingRequests) {
            outbox.replyError(item, ErrorCodes.NOT_ACCEPTING)
            return
        }
        val now = clock.nowMs()
        lockedUntilMs?.let { until ->
            if (now < until) {
                outbox.send(ErrorMessage(ErrorCodes.LOCKED, re = item.id, retryAfterMs = until - now))
                return
            }
            lockedUntilMs = null
            failures = 0
        }
        val peer = Fingerprint.ofCertificate(outbox.connection.peerCertificateDer, hashing)
        if (DeviceId.parse(request.deviceId) != peer.deviceId) {
            outbox.replyError(item, ErrorCodes.IDENTITY_MISMATCH, "deviceId does not match the TLS certificate")
            return
        }
        pending?.let { older ->
            pending = null
            older.outbox.send(ErrorMessage(ErrorCodes.CANCELLED, "a newer request replaced this one"))
            listener.onEvent(TargetEvent.CodeHidden(HideReason.REPLACED))
        }
        val code = PairingCode.generate(random)
        val server = srp.newServer(code.digits)
        val next = Pending(outbox, peer, request.name, request.platform, code, now, server)
        pending = next
        listener.onEvent(TargetEvent.CodeShown(request.name, request.platform, peer.deviceId, code, now + codeValidityMs))
        sendChallenge(next)
    }

    private fun onProof(outbox: Outbox, item: Inbound, proof: PairProof) {
        val current = pending
        if (current == null || current.outbox !== outbox || current.stage != Stage.CHALLENGED) {
            outbox.replyError(item, ErrorCodes.NO_PENDING)
            return
        }
        if (isExpired(current)) {
            expire(current)
            return
        }
        val a = Base64Bytes.decode(proof.a)
        val m1 = Base64Bytes.decode(proof.m1)
        val m2 = if (a != null && m1 != null) current.server.verify(a, m1) else null
        if (m2 == null) {
            failures++
            if (failures >= maxFailures) {
                val until = clock.nowMs() + lockoutMs
                lockedUntilMs = until
                pending = null
                outbox.send(ErrorMessage(ErrorCodes.LOCKED, re = item.id, retryAfterMs = lockoutMs))
                listener.onEvent(TargetEvent.CodeHidden(HideReason.LOCKED))
                listener.onEvent(TargetEvent.Locked(until))
            } else {
                val remaining = maxFailures - failures
                current.server = srp.newServer(current.code.digits)
                outbox.send(ErrorMessage(ErrorCodes.WRONG_CODE, re = item.id, remaining = remaining))
                listener.onEvent(TargetEvent.WrongCode(remaining))
                sendChallenge(current)
            }
            return
        }
        failures = 0
        current.sessionKey = current.server.sessionKey()
        current.stage = Stage.AWAITING_BIND
        outbox.send(PairConfirm(Base64Bytes.encode(m2)))
    }

    private fun onBind(outbox: Outbox, item: Inbound, bind: PairBind) {
        val current = pending
        val key = current?.sessionKey
        if (current == null || current.outbox !== outbox || current.stage != Stage.AWAITING_BIND || key == null) {
            outbox.replyError(item, ErrorCodes.UNEXPECTED, "no pairing awaits pair.bind")
            return
        }
        pending = null
        val mac = Base64Bytes.decode(bind.mac)
        val valid = DeviceId.parse(bind.deviceId) == current.peer.deviceId &&
            mac != null && PairingBinding.verify(hashing, key, sender = current.peer, receiver = local.fingerprint, mac = mac)
        if (!valid) {
            outbox.replyError(item, ErrorCodes.BIND_FAILED)
            listener.onEvent(TargetEvent.CodeHidden(HideReason.FAILED))
            listener.onEvent(TargetEvent.Failed(ErrorCodes.BIND_FAILED))
            return
        }
        val record = PairingRecord(
            deviceId = current.peer.deviceId,
            name = bind.name,
            platform = bind.platform,
            fingerprint = current.peer.hex,
            pairedAt = clock.nowMs(),
        )
        outbox.send(
            PairBind(
                local.deviceId.value, local.name, local.platform,
                Base64Bytes.encode(PairingBinding.mac(hashing, key, own = local.fingerprint, peer = current.peer)),
            ),
        )
        listener.onEvent(TargetEvent.CodeHidden(HideReason.USED))
        listener.onEvent(TargetEvent.Paired(record))
    }

    private fun onError(outbox: Outbox) {
        if (pending?.outbox === outbox) {
            pending = null
            listener.onEvent(TargetEvent.CodeHidden(HideReason.CANCELLED))
        }
    }

    private fun sendChallenge(p: Pending) {
        val challenge = p.server.challenge()
        p.outbox.send(PairChallenge(Base64Bytes.encode(challenge.salt), Base64Bytes.encode(challenge.b)))
    }

    private fun isExpired(p: Pending): Boolean = clock.nowMs() - p.createdAt >= codeValidityMs

    private fun expire(p: Pending) {
        pending = null
        p.outbox.send(ErrorMessage(ErrorCodes.EXPIRED))
        listener.onEvent(TargetEvent.CodeHidden(HideReason.EXPIRED))
    }

    private enum class Stage { CHALLENGED, AWAITING_BIND }

    private class Pending(
        val outbox: Outbox,
        val peer: Fingerprint,
        val name: String,
        val platform: Platform,
        val code: PairingCode,
        val createdAt: Long,
        var server: SrpServer,
    ) {
        var stage = Stage.CHALLENGED
        var sessionKey: ByteArray? = null
    }

    public companion object {
        /** A code is valid for 120 seconds (A.6). */
        public const val CODE_VALIDITY_MS: Long = 120_000

        /** Failed proofs that lock pairing (A.6). */
        public const val MAX_FAILURES: Int = 5

        /** How long pairing stays locked (A.6). */
        public const val LOCKOUT_MS: Long = 5 * 60_000
    }
}

/** What the target's pairing screen reacts to. */
public sealed class TargetEvent {
    /** Show "<name> wants to connect. Code: 482 913" until [expiresAtMs]. */
    public data class CodeShown(
        val name: String,
        val platform: Platform,
        val deviceId: DeviceId,
        val code: PairingCode,
        val expiresAtMs: Long,
    ) : TargetEvent()

    /** Remove the code from the screen. */
    public data class CodeHidden(val reason: HideReason) : TargetEvent()

    /** The requester typed a wrong code; the code stays valid for [remaining] more tries overall. */
    public data class WrongCode(val remaining: Int) : TargetEvent()

    /** Pairing is locked until [untilMs]. */
    public data class Locked(val untilMs: Long) : TargetEvent()

    /** Pairing succeeded; store [record] and pin its fingerprint. */
    public data class Paired(val record: PairingRecord) : TargetEvent()

    /** Pairing failed with an [ErrorCodes] value. */
    public data class Failed(val code: String) : TargetEvent()
}

/** Why a code left the screen. */
public enum class HideReason { USED, EXPIRED, REPLACED, LOCKED, CANCELLED, FAILED }
