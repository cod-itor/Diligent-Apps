package com.ditarector.diligent.sync.session

import com.ditarector.diligent.sync.manifest.ConflictKind
import com.ditarector.diligent.sync.manifest.Manifest
import com.ditarector.diligent.sync.manifest.SyncPlan

/** Where a [SyncSession] stands. Every change is reported to its listener. */
public sealed class SyncState {
    /** True once the session is over. */
    public open val isFinal: Boolean get() = false

    /** Not started. */
    public data object Idle : SyncState()

    /** Responder: show "<Device> wants to sync <project>. Allow?", then [SyncSession.approve] or [SyncSession.deny]. */
    public data class AwaitingApproval(val deviceName: String, val projectName: String?) : SyncState()

    /** Initiator: `sync.hello` sent, waiting for the responder's manifest (and its user's approval). */
    public data object AwaitingManifest : SyncState()

    /** Responder: manifest sent, waiting for the initiator's manifest and plan. */
    public data object AwaitingPlan : SyncState()

    /** Initiator: show [plan] and call [SyncSession.confirm] or [SyncSession.cancel]. */
    public data class PlanReady(val plan: SyncPlan) : SyncState()

    /** Responder: the initiator is showing [plan] (this device's view) to its user. */
    public data class AwaitingConfirm(val plan: SyncPlan) : SyncState()

    /** Snapshot taken, files moving. [pendingFiles] still to arrive here. */
    public data class Transferring(val pendingFiles: Int) : SyncState()

    /** Both sides finished. */
    public data class Done(val result: SyncResult) : SyncState() {
        override val isFinal: Boolean get() = true
    }

    /** Stopped by the user here, or by the peer when [byPeer]. Files already renamed into place stay. */
    public data class Cancelled(val byPeer: Boolean) : SyncState() {
        override val isFinal: Boolean get() = true
    }

    /** Stopped by an error ([code] from [com.ditarector.diligent.sync.message.ErrorCodes]), raised by the peer when [byPeer]. */
    public data class Failed(val code: String, val message: String? = null, val byPeer: Boolean = false) : SyncState() {
        override val isFinal: Boolean get() = true
    }
}

/**
 * What a finished sync did on this device.
 *
 * @property baseline the manifest to store for this peer and project, or null
 *   when the two sides did not end with the same view (for example because
 *   their ignore rules differ). Null means: delete the stored baseline, so the
 *   next sync uses first-sync rules, which never delete.
 * @property snapshotId the snapshot taken before changes here, for Undo; null
 *   when nothing changed on this device.
 */
public data class SyncResult(
    val baseline: Manifest?,
    val snapshotId: String?,
    val received: List<String>,
    val deleted: List<String>,
    val conflicts: List<ConflictOutcome>,
)

/**
 * A conflict as it ended on this device. For [ConflictKind.BOTH_CHANGED]
 * [copyPath] is where the peer's version was saved; the app opens its compare
 * screen (keep mine, keep theirs, keep both) on [path] and [copyPath].
 */
public data class ConflictOutcome(val path: String, val kind: ConflictKind, val copyPath: String?)
