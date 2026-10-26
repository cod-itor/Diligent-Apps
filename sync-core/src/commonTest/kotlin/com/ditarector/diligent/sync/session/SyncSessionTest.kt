package com.ditarector.diligent.sync.session

import com.ditarector.diligent.sync.device.Approval
import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.device.PairingRecord
import com.ditarector.diligent.sync.device.Platform
import com.ditarector.diligent.sync.fakes.FakeConnection
import com.ditarector.diligent.sync.fakes.FakeFileStore
import com.ditarector.diligent.sync.fakes.TestHashing
import com.ditarector.diligent.sync.manifest.ConflictKind
import com.ditarector.diligent.sync.manifest.IgnoreRules
import com.ditarector.diligent.sync.manifest.Manifest
import com.ditarector.diligent.sync.manifest.ManifestBuilder
import com.ditarector.diligent.sync.manifest.PlanConflict
import com.ditarector.diligent.sync.manifest.PlanDeletion
import com.ditarector.diligent.sync.manifest.Side
import com.ditarector.diligent.sync.message.ErrorCodes
import com.ditarector.diligent.sync.message.ErrorMessage
import com.ditarector.diligent.sync.message.FileData
import com.ditarector.diligent.sync.message.FileRequest
import com.ditarector.diligent.sync.message.SyncDone
import com.ditarector.diligent.sync.message.SyncHello
import com.ditarector.diligent.sync.message.SyncManifest
import com.ditarector.diligent.sync.message.SyncPlanMessage
import com.ditarector.diligent.sync.port.Snapshotter
import com.ditarector.diligent.sync.wire.Inbound
import com.ditarector.diligent.sync.wire.Outbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncSessionTest {
    private class FakeSnapshotter(private val log: MutableList<String>) : Snapshotter {
        var taken = 0
        var failWith: String? = null

        override fun snapshot(projectId: String, label: String): String {
            failWith?.let { throw IllegalStateException(it) }
            taken++
            log += "snapshot"
            return "snap-$taken"
        }
    }

    private inner class Device(val name: String, val platform: Platform, var allowEnv: Boolean = false) {
        val store = FakeFileStore()
        val snapshots = FakeSnapshotter(store.log)
        val fingerprint = Fingerprint.ofCertificate("$name cert".encodeToByteArray(), TestHashing)
        val states = mutableListOf<SyncState>()

        fun record(approval: Approval = Approval.ALWAYS) =
            PairingRecord(fingerprint.deviceId, name, platform, fingerprint.hex, pairedAt = 0, approval = approval)

        fun manifest(): Manifest = ManifestBuilder.build(store, IgnoreRules(store, allowEnv))
    }

    private val phone = Device("Phone", Platform.ANDROID)
    private val laptop = Device("Laptop", Platform.MACOS)

    /** Two sessions joined by fake connections; [pump] delivers queued frames in order. */
    private inner class Link(
        baseline: Manifest? = null,
        approval: Approval = Approval.ALWAYS,
        val tamper: (Inbound) -> Inbound = { it },
    ) {
        val toResponder = FakeConnection()
        val toInitiator = FakeConnection()
        private val queue = ArrayDeque<Pair<SyncSession, Inbound>>()

        val initiator = SyncSession(
            SyncSession.Role.INITIATOR, Outbox(toResponder), laptop.record(), "proj-1", "shop",
            phone.store, phone.snapshots, IgnoreRules(phone.store, phone.allowEnv), baseline, { phone.manifest() },
        ) { phone.states += it }

        val responder = SyncSession(
            SyncSession.Role.RESPONDER, Outbox(toInitiator), phone.record(approval), "proj-1", "shop",
            laptop.store, laptop.snapshots, IgnoreRules(laptop.store, laptop.allowEnv), baseline, { laptop.manifest() },
        ) { laptop.states += it }

        init {
            toResponder.onSent = { queue.addLast(responder to tamper(it)) }
            toInitiator.onSent = { queue.addLast(initiator to it) }
        }

        fun pump() {
            while (true) {
                val (session, item) = queue.removeFirstOrNull() ?: return
                // sync.hello is routed by the app, which creates the responder session from it.
                if ((item as? Inbound.Received)?.message is SyncHello) {
                    assertFalse(session.handle(item))
                    continue
                }
                assertTrue(session.handle(item), "session consumed $item")
            }
        }

        fun startAndPlan(): Link = apply {
            initiator.start()
            pump()
            if (responder.state is SyncState.AwaitingApproval) return@apply
            responder.start()
            pump()
        }

        fun run(): Link = apply {
            startAndPlan()
            initiator.confirm()
            pump()
        }
    }

    private fun filesOf(d: Device) = d.store.files.keys.sorted().associateWith { d.store.text(it) }

    @Test
    fun happyPathTransfersBothWaysDeletesAndAgreesOnBaseline() {
        // Baseline: both had a.txt, b.txt, c.txt, d.txt, same.txt.
        for (d in listOf(phone, laptop)) {
            d.store.put("a.txt", "a1").put("b.txt", "b1").put("c.txt", "c1").put("d.txt", "d1").put("same.txt", "s")
        }
        val baseline = phone.manifest()
        phone.store.put("a.txt", "a2-phone")          // changed here: send
        laptop.store.put("b.txt", "b2-laptop")        // changed there: receive
        phone.store.delete("c.txt")                   // deleted here: delete there
        laptop.store.delete("d.txt")                  // deleted there: delete here
        phone.store.put("new-on-phone.sh", "#!/bin/sh", executable = true)
        phone.store.log.clear(); laptop.store.log.clear()

        val link = Link(baseline).run()

        val phoneDone = assertIs<SyncState.Done>(phone.states.last()).result
        val laptopDone = assertIs<SyncState.Done>(laptop.states.last()).result
        assertEquals(filesOf(phone), filesOf(laptop))
        assertEquals(setOf("a.txt", "b.txt", "new-on-phone.sh", "same.txt"), phone.store.files.keys)
        assertEquals("a2-phone", laptop.store.text("a.txt"))
        assertEquals("b2-laptop", phone.store.text("b.txt"))
        assertTrue(laptop.store.files.getValue("new-on-phone.sh").executable, "executable bit travels")

        assertNotNull(phoneDone.baseline)
        assertTrue(phoneDone.baseline!!.sameContentAs(laptopDone.baseline!!))
        assertTrue(phoneDone.baseline!!.sameContentAs(phone.manifest()))
        assertEquals(listOf("b.txt"), phoneDone.received)
        assertEquals(listOf("d.txt"), phoneDone.deleted)
        assertEquals(listOf("c.txt"), laptopDone.deleted)
        assertEquals("snap-1", phoneDone.snapshotId)
        assertEquals(link.initiator.state, phone.states.last())
    }

    @Test
    fun snapshotComesBeforeAnyWriteOrDelete() {
        for (d in listOf(phone, laptop)) d.store.put("x.txt", "1").put("y.txt", "1")
        val baseline = phone.manifest()
        laptop.store.put("x.txt", "2")
        laptop.store.delete("y.txt")
        phone.store.log.clear()
        laptop.store.log.clear()
        Link(baseline).run()
        assertEquals(listOf("snapshot", "delete y.txt", "commit x.txt"), phone.store.log)
        assertTrue(laptop.store.log.isEmpty(), "laptop only sent, so it changed nothing")
    }

    @Test
    fun noSnapshotWhenNothingChangesOnADevice() {
        phone.store.put("only-phone.txt", "p")
        Link().run()
        assertEquals(0, phone.snapshots.taken, "phone only sends")
        assertEquals(1, laptop.snapshots.taken)
        assertNull(assertIs<SyncState.Done>(phone.states.last()).result.snapshotId)
    }

    @Test
    fun firstSyncIntoAnEmptyProjectReceivesEverything() {
        phone.store.put(".diligent/project.json", """{"projectId":"proj-1","name":"shop"}""")
        phone.store.put("src/index.js", "x".repeat(10))
        phone.store.put("empty.txt", "")
        phone.store.put("node_modules/react/index.js", "never")
        Link(baseline = null).run()
        assertEquals(setOf(".diligent/project.json", "empty.txt", "src/index.js"), laptop.store.files.keys)
        assertEquals("", laptop.store.text("empty.txt"))
        assertIs<SyncState.Done>(laptop.states.last())
    }

    @Test
    fun largeFileArrivesInChunks() {
        val big = (0 until 700_000).joinToString("") { ('a' + it % 26).toString() }
        phone.store.put("big.txt", big)
        val link = Link().run()
        assertEquals(big, laptop.store.text("big.txt"))
        val chunks = link.toResponder.sent.filterIsInstance<Inbound.FileChunk>()
        assertEquals(listOf(0L, 262_144L, 524_288L), chunks.map { it.header.offset })
        assertEquals(700_000, chunks.sumOf { it.header.length })
    }

    @Test
    fun conflictKeepsLocalAndSavesPeersCopyOnBothSides() {
        phone.store.put("notes.md", "phone version")
        laptop.store.put("notes.md", "laptop version")
        Link(baseline = null).run()
        assertEquals("phone version", phone.store.text("notes.md"))
        assertEquals("laptop version", phone.store.text("notes (from Laptop).md"))
        assertEquals("laptop version", laptop.store.text("notes.md"))
        assertEquals("phone version", laptop.store.text("notes (from Phone).md"))
        val result = assertIs<SyncState.Done>(phone.states.last()).result
        assertEquals(listOf(ConflictOutcome("notes.md", ConflictKind.BOTH_CHANGED, "notes (from Laptop).md")), result.conflicts)
        assertFalse("notes.md" in result.baseline!!, "unresolved conflict stays out of the baseline")
    }

    @Test
    fun deletedHereChangedThereRestoresTheChangedFile() {
        for (d in listOf(phone, laptop)) d.store.put("keep.txt", "v1")
        val baseline = phone.manifest()
        phone.store.delete("keep.txt")
        laptop.store.put("keep.txt", "v2")
        Link(baseline).run()
        assertEquals("v2", phone.store.text("keep.txt"))
        assertEquals("v2", laptop.store.text("keep.txt"))
        val result = assertIs<SyncState.Done>(phone.states.last()).result
        assertEquals(ConflictKind.DELETED_LOCAL, result.conflicts.single().kind)
        assertTrue("keep.txt" in result.baseline!!)
    }

    @Test
    fun planIsShownBeforeConfirmAndNothingMovesUntilThen() {
        phone.store.put("a.txt", "1")
        val link = Link().startAndPlan()
        val ready = assertIs<SyncState.PlanReady>(link.initiator.state)
        assertEquals(listOf("a.txt"), ready.plan.send)
        val responderView = assertIs<SyncState.AwaitingConfirm>(link.responder.state)
        assertEquals(listOf("a.txt"), responderView.plan.receive)
        assertTrue(laptop.store.files.isEmpty())
        assertEquals(0, laptop.snapshots.taken)
    }

    @Test
    fun cancelBeforeConfirmChangesNothing() {
        phone.store.put("a.txt", "1")
        val link = Link().startAndPlan()
        link.initiator.cancel()
        link.pump()
        assertEquals(SyncState.Cancelled(byPeer = false), link.initiator.state)
        assertEquals(SyncState.Cancelled(byPeer = true), link.responder.state)
        assertTrue(laptop.store.files.isEmpty())
        assertEquals(0, phone.snapshots.taken + laptop.snapshots.taken)
    }

    @Test
    fun cancelDuringTransferDiscardsPartialFiles() {
        phone.store.put("big.bin", "z".repeat(600_000))
        var chunks = 0
        lateinit var link: Link
        link = Link(tamper = { item ->
            if (item is Inbound.FileChunk && ++chunks == 2) link.responder.cancel()
            item
        })
        link.run()
        assertEquals(SyncState.Cancelled(byPeer = false), link.responder.state)
        assertEquals(SyncState.Cancelled(byPeer = true), link.initiator.state)
        assertTrue(laptop.store.temps.isEmpty(), "partial temp file discarded")
        assertFalse("big.bin" in laptop.store.files)
    }

    @Test
    fun askApprovalWaitsForTheUser() {
        phone.store.put("a.txt", "1")
        val link = Link(approval = Approval.ASK)
        link.initiator.start()
        link.pump()
        link.responder.start()
        link.pump()
        assertEquals(SyncState.AwaitingApproval("Phone", "shop"), link.responder.state)
        assertEquals(SyncState.AwaitingManifest, link.initiator.state)
        assertTrue(link.toInitiator.sent.isEmpty(), "no manifest before the user allows")
        link.responder.approve()
        link.pump()
        assertIs<SyncState.PlanReady>(link.initiator.state)
        link.initiator.confirm()
        link.pump()
        assertEquals("1", laptop.store.text("a.txt"))
    }

    @Test
    fun askApprovalDenied() {
        val link = Link(approval = Approval.ASK)
        link.initiator.start()
        link.pump()
        link.responder.start()
        link.responder.deny()
        link.pump()
        assertEquals(SyncState.Cancelled(byPeer = false), link.responder.state)
        assertEquals(ErrorCodes.DENIED, assertIs<SyncState.Failed>(link.initiator.state).code)
    }

    @Test
    fun hashMismatchIsRejectedAndTheFileIsNotReplaced() {
        laptop.store.put("a.txt", "original")
        phone.store.put("a.txt", "original")
        val baseline = phone.manifest()
        phone.store.put("a.txt", "new content")
        val link = Link(baseline).startAndPlan()
        // The file changes again after the manifest was built, keeping its size.
        phone.store.put("a.txt", "NEW CONTENT")
        link.initiator.confirm()
        link.pump()
        val failed = assertIs<SyncState.Failed>(link.responder.state)
        assertEquals(ErrorCodes.HASH_MISMATCH, failed.code)
        assertFalse(failed.byPeer)
        assertEquals(ErrorCodes.HASH_MISMATCH, assertIs<SyncState.Failed>(link.initiator.state).code)
        assertTrue(assertIs<SyncState.Failed>(link.initiator.state).byPeer)
        assertEquals("original", laptop.store.text("a.txt"))
        assertTrue(laptop.store.temps.isEmpty())
    }

    @Test
    fun hashMismatchFromATamperedChunk() {
        phone.store.put("a.txt", "honest")
        val link = Link(tamper = { item ->
            if (item is Inbound.FileChunk) Inbound.FileChunk(item.id, item.header, "HONEST".encodeToByteArray()) else item
        }).run()
        assertEquals(ErrorCodes.HASH_MISMATCH, assertIs<SyncState.Failed>(link.responder.state).code)
        assertFalse("a.txt" in laptop.store.files)
    }

    @Test
    fun envFilesSyncOnlyWhenBothAllow() {
        phone.allowEnv = true
        phone.store.put(".env", "SECRET=1").put("a.txt", "a")
        Link().run()
        assertFalse(".env" in laptop.store.files, "laptop does not allow .env")
        assertEquals("a", laptop.store.text("a.txt"))

        laptop.allowEnv = true
        Link().run()
        assertEquals("SECRET=1", laptop.store.text(".env"))
    }

    @Test
    fun envFileIsNotDeletedWhenThePeerStopsAllowingIt() {
        phone.allowEnv = true
        laptop.allowEnv = true
        for (d in listOf(phone, laptop)) d.store.put(".env", "K=1").put("a.txt", "a")
        val baseline = phone.manifest()
        laptop.allowEnv = false
        Link(baseline).run()
        assertEquals("K=1", phone.store.text(".env"))
        assertTrue(assertIs<SyncState.Done>(phone.states.last()).result.conflicts.isEmpty())
    }

    @Test
    fun responderSkipsPathsItIgnoresAndBaselineIsDropped() {
        laptop.store.put(".diligentignore", "local-only/\n")
        phone.store.put(".diligentignore", "local-only/\n")
        val baseline = phone.manifest()
        phone.store.put(".diligentignore", "")
        phone.store.put("local-only/x.txt", "x")
        Link(baseline).run()
        // The new .diligentignore arrives, but the laptop still applied its own rules during this sync.
        assertFalse("local-only/x.txt" in laptop.store.files)
        assertNull(assertIs<SyncState.Done>(phone.states.last()).result.baseline, "views differ: next sync uses first-sync rules")
        assertNull(assertIs<SyncState.Done>(laptop.states.last()).result.baseline)
    }

    @Test
    fun snapshotFailureAbortsBeforeChanges() {
        laptop.store.put("a.txt", "1")
        phone.snapshots.failWith = "disk full"
        val link = Link().startAndPlan()
        link.initiator.confirm()
        link.pump()
        assertEquals(SyncSession.SNAPSHOT_FAILED, assertIs<SyncState.Failed>(link.initiator.state).code)
        assertIs<SyncState.Failed>(link.responder.state)
        assertFalse("a.txt" in phone.store.files)
    }

    @Test
    fun requestForAFileOutsideThePlanIsRefused() {
        phone.store.put("a.txt", "1").put("private.txt", "secret")
        val same = phone.manifest()
        val connection = FakeConnection()
        val session = SyncSession(
            SyncSession.Role.INITIATOR, Outbox(connection), laptop.record(), "proj-1", null,
            phone.store, phone.snapshots, IgnoreRules(phone.store), same, { phone.manifest() },
        ) { }
        session.start()
        session.handle(Inbound.Received("1", SyncManifest(same.entries)))
        assertTrue(assertIs<SyncState.PlanReady>(session.state).plan.isEmpty)
        session.confirm()
        session.handle(Inbound.Received("2", FileRequest("private.txt")))
        assertEquals(ErrorCodes.NOT_AVAILABLE, connection.messages.filterIsInstance<ErrorMessage>().single().code)
        assertTrue(connection.sent.none { it is Inbound.FileChunk })
    }

    @Test
    fun planWithEscapingPathIsRejected() {
        val connection = FakeConnection()
        val session = SyncSession(
            SyncSession.Role.RESPONDER, Outbox(connection), phone.record(), "proj-1", null,
            laptop.store, laptop.snapshots, IgnoreRules(laptop.store), null, { laptop.manifest() },
        ) { }
        session.start()
        val evil = com.ditarector.diligent.sync.manifest.ManifestEntry("../../.bashrc", 1, 1, "a".repeat(64))
        session.handle(Inbound.Received("1", SyncManifest(listOf(evil))))
        assertEquals(ErrorCodes.INVALID, assertIs<SyncState.Failed>(session.state).code)
    }

    @Test
    fun planReferringToFilesNotInTheManifestsIsRejected() {
        val connection = FakeConnection()
        val session = SyncSession(
            SyncSession.Role.RESPONDER, Outbox(connection), phone.record(), "proj-1", null,
            laptop.store, laptop.snapshots, IgnoreRules(laptop.store), null, { laptop.manifest() },
        ) { }
        session.start()
        session.handle(Inbound.Received("1", SyncManifest(emptyList())))
        session.handle(Inbound.Received("2", SyncPlanMessage(delete = listOf(PlanDeletion("notes.md", Side.REMOTE)))))
        assertEquals(ErrorCodes.INVALID, assertIs<SyncState.Failed>(session.state).code)
        assertTrue(laptop.store.log.isEmpty())
    }

    @Test
    fun unrequestedFileDataIsRejected() {
        phone.store.put("a.txt", "1")
        val link = Link().startAndPlan()
        link.initiator.confirm()
        // Before the pump delivers anything, slip in a chunk for a path nobody asked for.
        link.responder.handle(Inbound.FileChunk("x", FileData("other.txt", 0, 1), byteArrayOf(1)))
        assertEquals(ErrorCodes.UNEXPECTED, assertIs<SyncState.Failed>(link.responder.state).code)
        link.pump()
        assertFalse("other.txt" in laptop.store.files)
    }

    @Test
    fun outOfStateMessagesFailTheSession() {
        val connection = FakeConnection()
        val session = SyncSession(
            SyncSession.Role.INITIATOR, Outbox(connection), laptop.record(), "proj-1", null,
            phone.store, phone.snapshots, IgnoreRules(phone.store), null, { phone.manifest() },
        ) { }
        session.start()
        assertEquals(SyncHello("proj-1", null), connection.messages.single())
        session.handle(Inbound.Received("1", SyncDone(emptyList())))
        assertEquals(ErrorCodes.UNEXPECTED, assertIs<SyncState.Failed>(session.state).code)
        assertFalse(session.handle(Inbound.Received("2", com.ditarector.diligent.sync.message.RunStop)), "not sync traffic")
    }

    @Test
    fun conflictKindsAreMirroredForTheResponder() {
        for (d in listOf(phone, laptop)) d.store.put("x", "1")
        val baseline = phone.manifest()
        phone.store.put("x", "2")
        laptop.store.delete("x")
        val link = Link(baseline).startAndPlan()
        assertEquals(listOf(PlanConflict("x", ConflictKind.DELETED_REMOTE)), assertIs<SyncState.PlanReady>(link.initiator.state).plan.conflicts)
        assertEquals(listOf(PlanConflict("x", ConflictKind.DELETED_LOCAL)), assertIs<SyncState.AwaitingConfirm>(link.responder.state).plan.conflicts)
        link.initiator.confirm()
        link.pump()
        assertEquals("2", laptop.store.text("x"))
    }
}
