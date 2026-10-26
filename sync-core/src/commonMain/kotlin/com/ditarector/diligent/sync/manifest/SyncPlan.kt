package com.ditarector.diligent.sync.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a sync will do, from the point of view of the device holding it:
 * [send] goes from here to the peer, [receive] comes from the peer to here.
 *
 * The initiator computes the plan and sends it in `sync.plan` from its own
 * point of view; the responder reads it with [mirrored]. SPEC v1 lists
 * `delete` and `conflicts` without saying on which side they apply, so each
 * entry carries its [Side] or [ConflictKind] explicitly.
 */
@Serializable
public data class SyncPlan(
    val send: List<String> = emptyList(),
    val receive: List<String> = emptyList(),
    val delete: List<PlanDeletion> = emptyList(),
    val conflicts: List<PlanConflict> = emptyList(),
) {
    /** True when there is nothing to transfer, delete or resolve. */
    val isEmpty: Boolean get() = send.isEmpty() && receive.isEmpty() && delete.isEmpty() && conflicts.isEmpty()

    /** Paths deleted on this device. */
    val deleteHere: List<String> get() = delete.filter { it.side == Side.LOCAL }.map { it.path }

    /** Paths deleted on the peer. */
    val deleteThere: List<String> get() = delete.filter { it.side == Side.REMOTE }.map { it.path }

    /** The same plan seen from the peer. */
    public fun mirrored(): SyncPlan = SyncPlan(
        send = receive,
        receive = send,
        delete = delete.map { PlanDeletion(it.path, it.side.other) },
        conflicts = conflicts.map { PlanConflict(it.path, it.kind.mirrored) },
    )
}

/** Which device of the pair, relative to whoever holds the [SyncPlan]. */
@Serializable
public enum class Side {
    @SerialName("local") LOCAL,
    @SerialName("remote") REMOTE,
    ;

    /** The other device. */
    public val other: Side get() = if (this == LOCAL) REMOTE else LOCAL
}

/** A file to delete on [side] because the other side deleted it and it was unchanged here (A.7). */
@Serializable
public data class PlanDeletion(val path: String, val side: Side)

/** A path both sides touched, and how (A.7). */
@Serializable
public data class PlanConflict(val path: String, val kind: ConflictKind)

/** The comparison rows that end in "Conflict". */
@Serializable
public enum class ConflictKind {
    /**
     * Both versions exist and differ (changed on both, new on both, or the
     * first sync). Each device keeps its own file and saves the peer's as
     * `<name> (from <Device>).<ext>`.
     */
    @SerialName("both_changed") BOTH_CHANGED,

    /** Deleted here, changed on the peer: the changed file is kept, so it comes back here. */
    @SerialName("deleted_local") DELETED_LOCAL,

    /** Changed here, deleted on the peer: the changed file is kept, so it goes back there. */
    @SerialName("deleted_remote") DELETED_REMOTE,
    ;

    /** The same conflict seen from the peer. */
    public val mirrored: ConflictKind
        get() = when (this) {
            BOTH_CHANGED -> BOTH_CHANGED
            DELETED_LOCAL -> DELETED_REMOTE
            DELETED_REMOTE -> DELETED_LOCAL
        }
}
