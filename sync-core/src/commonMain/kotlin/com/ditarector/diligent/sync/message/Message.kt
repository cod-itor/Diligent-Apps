package com.ditarector.diligent.sync.message

import com.ditarector.diligent.sync.device.Platform
import com.ditarector.diligent.sync.manifest.ManifestEntry
import com.ditarector.diligent.sync.manifest.PlanConflict
import com.ditarector.diligent.sync.manifest.PlanDeletion
import com.ditarector.diligent.sync.manifest.SyncPlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every diligent-link v1 message (SPEC A.5 to A.9), one class per `type`.
 *
 * The `id` every frame carries is not a field here: [com.ditarector.diligent.sync.wire.Outbox]
 * assigns it when sending and [com.ditarector.diligent.sync.wire.Inbound] reports it when
 * receiving, so message values stay plain data that can be compared in tests.
 *
 * Where v1 names a message without listing its fields, the fields below are
 * sync-core's reading and are optional, so a peer that omits them still
 * decodes. Byte values (SRP numbers and MACs) are standard base64 with padding.
 */
@Serializable
public sealed class Message {
    /** True for messages allowed before the peer is paired (A.4): `pair.*` and `error`. */
    public val allowedBeforePairing: Boolean
        get() = this is PairRequest || this is PairChallenge || this is PairProof || this is PairConfirm ||
            this is PairBind || this is ErrorMessage
}

// A.6 Pairing

/** Requester to target: asks to pair. [deviceId] must match the TLS certificate. */
@Serializable
@SerialName("pair.request")
public data class PairRequest(val name: String, val platform: Platform, val deviceId: String) : Message()

/** Target to requester: SRP-6a salt and server public value `B`, base64. */
@Serializable
@SerialName("pair.challenge")
public data class PairChallenge(val salt: String, @SerialName("B") val b: String) : Message()

/** Requester to target: SRP-6a client public value `A` and proof `M1`, base64. */
@Serializable
@SerialName("pair.proof")
public data class PairProof(@SerialName("A") val a: String, @SerialName("M1") val m1: String) : Message()

/** Target to requester: SRP-6a server proof `M2`, base64. */
@Serializable
@SerialName("pair.confirm")
public data class PairConfirm(@SerialName("M2") val m2: String) : Message()

/**
 * Both directions: binds the TLS identities to the code. [mac] is base64 of
 * HMAC-SHA512(K, own fingerprint || peer fingerprint).
 */
@Serializable
@SerialName("pair.bind")
public data class PairBind(val deviceId: String, val name: String, val platform: Platform, val mac: String) : Message()

/** The sender forgot this pairing; the receiver should drop its record too. */
@Serializable
@SerialName("pair.forget")
public data object PairForget : Message()

// A.7 Sync

/**
 * Initiator to responder: wants to sync [projectId]. [name] is the project's
 * display name (optional in sync-core's reading of v1), shown in the approval
 * prompt and in "Receive project <name>?".
 */
@Serializable
@SerialName("sync.hello")
public data class SyncHello(val projectId: String, val name: String? = null) : Message()

/**
 * Each side's manifest. [allowEnv] tells the peer whether this device allows
 * `.env*` files for the project; v1 says they sync only when both allow it,
 * and only this flag lets the plan tell "not allowed" from "deleted".
 */
@Serializable
@SerialName("sync.manifest")
public data class SyncManifest(val entries: List<ManifestEntry>, val allowEnv: Boolean = false) : Message()

/** Initiator to responder: the plan, from the initiator's point of view. */
@Serializable
@SerialName("sync.plan")
public data class SyncPlanMessage(
    val send: List<String> = emptyList(),
    val receive: List<String> = emptyList(),
    val delete: List<PlanDeletion> = emptyList(),
    val conflicts: List<PlanConflict> = emptyList(),
) : Message() {
    public constructor(plan: SyncPlan) : this(plan.send, plan.receive, plan.delete, plan.conflicts)

    /** The plan as the initiator sees it. */
    public fun toPlan(): SyncPlan = SyncPlan(send, receive, delete, conflicts)
}

/** Initiator to responder: the user confirmed the plan; both sides snapshot and transfer. */
@Serializable
@SerialName("sync.confirm")
public data object SyncConfirm : Message()

/** Asks the peer for one file of the plan; answered with `file.data` frames. */
@Serializable
@SerialName("file.request")
public data class FileRequest(val path: String) : Message()

/**
 * Header of a file chunk; [length] raw bytes follow it on the wire (A.5).
 * A file of size n arrives as chunks with increasing [offset] until n bytes
 * have come; an empty file arrives as one chunk of length 0.
 */
@Serializable
@SerialName("file.data")
public data class FileData(val path: String, val offset: Long, val length: Int) : Message()

/** A side finished applying the plan; [manifest] is what it now considers the agreed baseline. */
@Serializable
@SerialName("sync.done")
public data class SyncDone(val manifest: List<ManifestEntry>) : Message()

/** Either side stops the sync. Files already renamed into place stay; the snapshot can undo them. */
@Serializable
@SerialName("sync.cancel")
public data class SyncCancel(val reason: String? = null) : Message()

// A.8 Remote run

/** Asks the host to run [profile] from `.diligent/run.json` of [projectId]. */
@Serializable
@SerialName("run.start")
public data class RunStart(val projectId: String, val profile: String) : Message()

/** Asks the host to stop the current run. */
@Serializable
@SerialName("run.stop")
public data object RunStop : Message()

/** Without [running], a request for the run state; with it, the host's answer. */
@Serializable
@SerialName("run.status")
public data class RunStatus(val running: Boolean? = null, val profile: String? = null) : Message()

/** One streamed output line of the run. */
@Serializable
@SerialName("run.log")
public data class RunLog(val line: String, val stream: LogStream = LogStream.STDOUT) : Message()

/** Which output stream a [RunLog] line came from. */
@Serializable
public enum class LogStream {
    @SerialName("stdout") STDOUT,
    @SerialName("stderr") STDERR,
}

/** The run ended with exit [code]. */
@Serializable
@SerialName("run.exit")
public data class RunExit(val code: Int) : Message()

/** The host's IP-restricted preview proxy for the run (A.8). */
@Serializable
@SerialName("run.preview")
public data class RunPreview(val host: String, val port: Int) : Message()

// A.9 AI relay

/** Without fields, a request; the desktop answers with [reachable] and [models] of its Ollama. */
@Serializable
@SerialName("ai.info")
public data class AiInfo(val reachable: Boolean? = null, val models: List<String>? = null) : Message()

/** Without fields, a request; the desktop answers with its IP-restricted Ollama proxy. */
@Serializable
@SerialName("ai.endpoint")
public data class AiEndpoint(val host: String? = null, val port: Int? = null) : Message()

// Errors

/**
 * Something went wrong or was refused; [code] is one of [ErrorCodes].
 * [re] is the `id` of the message this answers, when there is one,
 * [remaining] the pairing attempts left after a wrong code and
 * [retryAfterMs] how long pairing stays locked.
 */
@Serializable
@SerialName("error")
public data class ErrorMessage(
    val code: String,
    val message: String? = null,
    val re: String? = null,
    val remaining: Int? = null,
    val retryAfterMs: Long? = null,
) : Message()
