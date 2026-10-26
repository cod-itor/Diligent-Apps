package com.ditarector.diligent.sync.session

import com.ditarector.diligent.sync.conflict.ConflictNames
import com.ditarector.diligent.sync.device.Approval
import com.ditarector.diligent.sync.device.PairingRecord
import com.ditarector.diligent.sync.manifest.ConflictKind
import com.ditarector.diligent.sync.manifest.IgnoreRules
import com.ditarector.diligent.sync.manifest.Manifest
import com.ditarector.diligent.sync.manifest.ManifestComparer
import com.ditarector.diligent.sync.manifest.ManifestEntry
import com.ditarector.diligent.sync.manifest.ProjectPaths
import com.ditarector.diligent.sync.manifest.Side
import com.ditarector.diligent.sync.manifest.SyncPlan
import com.ditarector.diligent.sync.message.ErrorCodes
import com.ditarector.diligent.sync.message.ErrorMessage
import com.ditarector.diligent.sync.message.FileData
import com.ditarector.diligent.sync.message.FileRequest
import com.ditarector.diligent.sync.message.SyncCancel
import com.ditarector.diligent.sync.message.SyncConfirm
import com.ditarector.diligent.sync.message.SyncDone
import com.ditarector.diligent.sync.message.SyncHello
import com.ditarector.diligent.sync.message.SyncManifest
import com.ditarector.diligent.sync.message.SyncPlanMessage
import com.ditarector.diligent.sync.port.FileStore
import com.ditarector.diligent.sync.port.Snapshotter
import com.ditarector.diligent.sync.wire.Frames
import com.ditarector.diligent.sync.wire.Inbound
import com.ditarector.diligent.sync.wire.Outbox

/**
 * One sync of one project with one paired device (SPEC A.7):
 *
 * ```
 * initiator                         responder
 * sync.hello            ------>     (asks its user when approval is "ask")
 *                       <------     sync.manifest
 * sync.manifest, sync.plan ---->
 * (user confirms)
 * sync.confirm          ------>
 * snapshot, deletes                 snapshot, deletes
 * file.request / file.data  <---->  file.request / file.data
 * sync.done             <------>    sync.done
 * ```
 *
 * The initiator computes the plan with [ManifestComparer]; the responder
 * applies it as sent, after checking that every path is one it can accept,
 * and skips paths it ignores itself. Each side requests the files it needs,
 * receives them into a temporary file, checks the SHA-256 from the sender's
 * manifest and only then renames them into place. A side that is about to
 * write or delete anything snapshots through [Snapshotter] first; a side that
 * only sends changes nothing and takes none.
 *
 * `sync.hello` is not handled here: the responder's app looks up the project
 * it names, then creates a [Role.RESPONDER] session and calls [start].
 *
 * The session is a passive state machine: the app feeds it [handle] calls
 * from the connection and user actions ([start], [approve], [deny],
 * [confirm], [cancel]) on one thread, and watches [state] through
 * [listener]. Files are read and written synchronously through [store], so
 * the app runs the session off its UI thread.
 *
 * @param peer the paired device's record: its name labels conflict copies,
 *   its approval decides whether a responder asks first.
 * @param localManifest builds this device's manifest; called once, after
 *   approval, so the app can save open editors right before.
 * @param rules this device's ignore rules; [IgnoreRules.allowEnv] is its
 *   per-project `.env*` setting.
 * @param baseline the manifest agreed with [peer] at the last sync, or null.
 */
public class SyncSession(
    public val role: Role,
    private val outbox: Outbox,
    private val peer: PairingRecord,
    public val projectId: String,
    private val projectName: String?,
    private val store: FileStore,
    private val snapshotter: Snapshotter,
    private val rules: IgnoreRules,
    private val baseline: Manifest?,
    private val localManifest: () -> Manifest,
    private val listener: Listener,
) {
    /** Which end of the flow this device is. */
    public enum class Role { INITIATOR, RESPONDER }

    /** Hears every state change, synchronously. */
    public fun interface Listener {
        public fun onState(state: SyncState)
    }

    /** Current state. */
    public var state: SyncState = SyncState.Idle
        private set

    private var local: Manifest? = null
    private var remote: Manifest? = null
    private var remoteAllowsEnv = false
    private var plan: SyncPlan? = null
    private var serve: Set<String> = emptySet()
    private val incoming = LinkedHashMap<String, Incoming>()
    private val received = ArrayList<String>()
    private val deleted = ArrayList<String>()
    private val conflicts = ArrayList<ConflictOutcome>()
    private var snapshotId: String? = null
    private var localDone = false
    private var peerBaseline: Manifest? = null
    private var ownBaseline: Manifest? = null

    /**
     * Initiator: sends `sync.hello`. Responder (created when `sync.hello`
     * arrived): asks the user when the peer's approval is
     * [Approval.ASK], otherwise answers with this device's manifest.
     */
    public fun start() {
        check(state == SyncState.Idle) { "already started" }
        when (role) {
            Role.INITIATOR -> {
                move(SyncState.AwaitingManifest)
                outbox.send(SyncHello(projectId, projectName))
            }
            Role.RESPONDER ->
                if (peer.approval == Approval.ASK) move(SyncState.AwaitingApproval(peer.name, projectName)) else sendManifest()
        }
    }

    /** Responder: the user allowed the sync. */
    public fun approve() {
        check(state is SyncState.AwaitingApproval) { "nothing to approve" }
        sendManifest()
    }

    /** Responder: the user declined; the initiator sees [ErrorCodes.DENIED]. */
    public fun deny() {
        check(state is SyncState.AwaitingApproval) { "nothing to deny" }
        outbox.send(ErrorMessage(ErrorCodes.DENIED))
        move(SyncState.Cancelled(byPeer = false))
    }

    /** Initiator: the user accepted the plan. */
    public fun confirm() {
        check(role == Role.INITIATOR && state is SyncState.PlanReady) { "no plan to confirm" }
        beginTransfers()
    }

    /** Either side stops the sync. Partial files are discarded; completed ones stay. */
    public fun cancel() {
        if (state.isFinal) return
        outbox.send(SyncCancel("cancelled by the user"))
        discardIncoming()
        move(SyncState.Cancelled(byPeer = false))
    }

    /**
     * Handles one item from the connection. Returns false for traffic that is
     * not part of this session (`sync.hello`, pairing, run, AI relay), which
     * the caller routes elsewhere.
     */
    public fun handle(item: Inbound): Boolean {
        if (item is Inbound.FileChunk) {
            if (!state.isFinal) onChunk(item)
            return true
        }
        val message = (item as? Inbound.Received)?.message ?: return false
        if (state.isFinal) {
            return message is SyncManifest || message is SyncPlanMessage || message is SyncConfirm ||
                message is FileRequest || message is SyncDone || message is SyncCancel || message is ErrorMessage
        }
        when (message) {
            is SyncManifest -> onManifest(message)
            is SyncPlanMessage -> onPlan(message)
            is SyncConfirm -> if (role == Role.RESPONDER && state is SyncState.AwaitingConfirm) beginTransfers() else unexpected("sync.confirm")
            is FileRequest -> onFileRequest(item, message)
            is SyncDone -> onPeerDone(message)
            is SyncCancel -> {
                discardIncoming()
                move(SyncState.Cancelled(byPeer = true))
            }
            is ErrorMessage -> {
                discardIncoming()
                move(SyncState.Failed(message.code, message.message, byPeer = true))
            }
            else -> return false
        }
        return true
    }

    private fun sendManifest() {
        val manifest = localManifest()
        local = manifest
        move(SyncState.AwaitingPlan)
        outbox.send(SyncManifest(manifest.entries, rules.allowEnv))
    }

    private fun onManifest(message: SyncManifest) {
        val expected = (role == Role.INITIATOR && state == SyncState.AwaitingManifest) ||
            (role == Role.RESPONDER && state == SyncState.AwaitingPlan && remote == null)
        if (!expected) return unexpected("sync.manifest")
        val manifest = validManifest(message.entries) ?: return fail(ErrorCodes.INVALID, "invalid manifest")
        remote = manifest
        remoteAllowsEnv = message.allowEnv
        if (role == Role.RESPONDER) return
        val mine = localManifest()
        local = mine
        val computed = ManifestComparer.compare(mine, manifest, baseline, ::excluded)
        plan = computed
        move(SyncState.PlanReady(computed))
        outbox.send(SyncManifest(mine.entries, rules.allowEnv))
        outbox.send(SyncPlanMessage(computed))
    }

    private fun onPlan(message: SyncPlanMessage) {
        val mine = local
        val theirs = remote
        if (role != Role.RESPONDER || state != SyncState.AwaitingPlan || mine == null || theirs == null) return unexpected("sync.plan")
        val view = message.toPlan().mirrored()
        if (!acceptable(view, mine, theirs)) return fail(ErrorCodes.INVALID, "plan refers to paths this device cannot accept")
        // Paths this device ignores are left alone here; the baselines then differ and both sides drop theirs.
        val applied = SyncPlan(
            send = view.send.filterNot(::excluded),
            receive = view.receive.filterNot(::excluded),
            delete = view.delete.filterNot { excluded(it.path) },
            conflicts = view.conflicts.filterNot { excluded(it.path) },
        )
        plan = applied
        move(SyncState.AwaitingConfirm(applied))
    }

    private fun beginTransfers() {
        val current = plan ?: return fail(ErrorCodes.UNEXPECTED, "no plan")
        val theirs = remote ?: return fail(ErrorCodes.UNEXPECTED, "no manifest")
        serve = (
            current.send + current.conflicts.filter { it.kind != ConflictKind.DELETED_LOCAL }.map { it.path }
            ).toSet()
        val wanted = ArrayList<Incoming>()
        for (path in current.receive) wanted += Incoming(path, path, theirs[path]!!)
        val reserved = HashSet<String>()
        for (conflict in current.conflicts) {
            var copy: String? = null
            when (conflict.kind) {
                ConflictKind.DELETED_LOCAL -> wanted += Incoming(conflict.path, conflict.path, theirs[conflict.path]!!)
                ConflictKind.BOTH_CHANGED -> {
                    copy = ConflictNames.uniqueCopyName(conflict.path, peer.name) { store.exists(it) || it in reserved || it in theirs }
                    reserved += copy
                    wanted += Incoming(conflict.path, copy, theirs[conflict.path]!!)
                }
                ConflictKind.DELETED_REMOTE -> Unit
            }
            conflicts += ConflictOutcome(conflict.path, conflict.kind, copy)
        }
        val deletions = current.deleteHere
        if (wanted.isNotEmpty() || deletions.isNotEmpty()) {
            snapshotId = try {
                snapshotter.snapshot(projectId, "Before sync with ${peer.name}")
            } catch (e: Exception) {
                return fail(SNAPSHOT_FAILED, e.message)
            }
        }
        for (path in deletions) {
            store.delete(path)
            deleted += path
        }
        wanted.forEach { incoming[it.path] = it }
        move(SyncState.Transferring(incoming.size))
        // The initiator's confirm goes out only now, so a fast peer's replies find it ready.
        if (role == Role.INITIATOR) outbox.send(SyncConfirm)
        for (file in wanted) outbox.send(FileRequest(file.path))
        finishLocallyIfComplete()
    }

    private fun onFileRequest(item: Inbound, request: FileRequest) {
        if (state !is SyncState.Transferring) return unexpected("file.request")
        val entry = local?.get(request.path)
        if (request.path !in serve || entry == null) {
            outbox.replyError(item, ErrorCodes.NOT_AVAILABLE, request.path)
            return
        }
        var offset = 0L
        do {
            val length = minOf(Frames.SEND_CHUNK_BYTES.toLong(), entry.size - offset).toInt()
            val bytes = store.read(request.path, offset, length)
            if (bytes.size != length) {
                // The file changed since the manifest was built; the peer's hash check would fail anyway.
                outbox.replyError(item, ErrorCodes.NOT_AVAILABLE, "${request.path} changed during the sync")
                return
            }
            outbox.sendFileData(FileData(request.path, offset, length), bytes)
            offset += length
        } while (offset < entry.size)
    }

    private fun onChunk(chunk: Inbound.FileChunk) {
        val header = chunk.header
        val file = incoming[header.path]
        if (state !is SyncState.Transferring || file == null) return fail(ErrorCodes.UNEXPECTED, "file.data for ${header.path} was not requested")
        if (header.offset != file.received || file.received + header.length > file.entry.size) {
            return fail(ErrorCodes.INVALID, "file.data for ${header.path} out of order or too long")
        }
        store.writeTemp(file.path, header.offset, chunk.bytes)
        file.received += header.length
        if (file.received < file.entry.size) return
        if (store.tempSha256(file.path) != file.entry.sha256) {
            return fail(ErrorCodes.HASH_MISMATCH, file.path)
        }
        store.commitTemp(file.path, file.target, file.entry.executable)
        incoming.remove(file.path)
        if (file.target == file.path) received += file.path
        move(SyncState.Transferring(incoming.size))
        finishLocallyIfComplete()
    }

    private fun finishLocallyIfComplete() {
        if (localDone || incoming.isNotEmpty() || state !is SyncState.Transferring) return
        val agreed = ManifestComparer.agreedBaseline(local!!, remote!!, plan!!, ::excluded)
        ownBaseline = agreed
        localDone = true
        outbox.send(SyncDone(agreed.entries))
        finishIfBothDone()
    }

    private fun onPeerDone(message: SyncDone) {
        if (state !is SyncState.Transferring || peerBaseline != null) return unexpected("sync.done")
        peerBaseline = validManifest(message.manifest) ?: return fail(ErrorCodes.INVALID, "invalid sync.done manifest")
        finishIfBothDone()
    }

    private fun finishIfBothDone() {
        val mine = ownBaseline ?: return
        val theirs = peerBaseline ?: return
        val result = SyncResult(
            baseline = mine.takeIf { it.sameContentAs(theirs) },
            snapshotId = snapshotId,
            received = received.toList(),
            deleted = deleted.toList(),
            conflicts = conflicts.toList(),
        )
        move(SyncState.Done(result))
    }

    /**
     * Paths left out of this sync on this device: what its rules ignore, and
     * `.env*` files unless both devices allow them (A.7).
     */
    private fun excluded(path: String): Boolean =
        rules.isIgnored(path, false) || (IgnoreRules.isEnvPath(path) && !(rules.allowEnv && remoteAllowsEnv))

    /** Every path in a received plan must be valid and backed by the manifest it claims to come from. */
    private fun acceptable(view: SyncPlan, mine: Manifest, theirs: Manifest): Boolean {
        val paths = view.send + view.receive + view.delete.map { it.path } + view.conflicts.map { it.path }
        if (!paths.all(ProjectPaths::isValid) || paths.size != paths.toSet().size) return false
        return view.send.all { it in mine } &&
            view.receive.all { it in theirs } &&
            view.delete.all { if (it.side == Side.LOCAL) it.path in mine else it.path in theirs } &&
            view.conflicts.all {
                when (it.kind) {
                    ConflictKind.BOTH_CHANGED -> it.path in mine && it.path in theirs
                    ConflictKind.DELETED_LOCAL -> it.path in theirs && it.path !in mine
                    ConflictKind.DELETED_REMOTE -> it.path in mine && it.path !in theirs
                }
            }
    }

    private fun validManifest(entries: List<ManifestEntry>): Manifest? {
        val ok = entries.all { ProjectPaths.isValid(it.path) && it.size >= 0 && it.sha256.length == 64 && it.sha256.all { c -> c in HEX } }
        if (!ok || entries.size != entries.map { it.path }.toSet().size) return null
        return Manifest(entries)
    }

    private fun unexpected(type: String) = fail(ErrorCodes.UNEXPECTED, "$type not expected in $state")

    private fun fail(code: String, message: String?) {
        outbox.send(ErrorMessage(code, message))
        discardIncoming()
        move(SyncState.Failed(code, message, byPeer = false))
    }

    private fun discardIncoming() {
        incoming.keys.forEach(store::discardTemp)
        incoming.clear()
    }

    private fun move(next: SyncState) {
        state = next
        listener.onState(next)
    }

    private class Incoming(val path: String, val target: String, val entry: ManifestEntry) {
        var received = 0L
    }

    public companion object {
        /** Error code sent when this device could not take its snapshot. */
        public const val SNAPSHOT_FAILED: String = "snapshot_failed"
        private const val HEX = "0123456789abcdef"
    }
}
