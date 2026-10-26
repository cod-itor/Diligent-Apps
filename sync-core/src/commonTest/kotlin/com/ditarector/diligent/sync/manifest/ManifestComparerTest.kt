package com.ditarector.diligent.sync.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One test per row of the SPEC A.7 comparison table, plus the first-sync rows. */
class ManifestComparerTest {
    private fun e(path: String, content: String, executable: Boolean = false, mtime: Long = 1) =
        ManifestEntry(path, content.length.toLong(), mtime, content.padEnd(64, '0').take(64), executable)

    private fun m(vararg entries: ManifestEntry) = Manifest(entries.toList())

    private fun plan(
        send: List<String> = emptyList(),
        receive: List<String> = emptyList(),
        delete: List<PlanDeletion> = emptyList(),
        conflicts: List<PlanConflict> = emptyList(),
    ) = SyncPlan(send, receive, delete, conflicts)

    private val base = m(e("a.txt", "v1"))

    /** Checks the plan, and that the peer computing from its side gets the mirror image. */
    private fun assertPlan(expected: SyncPlan, local: Manifest, remote: Manifest, baseline: Manifest?) {
        val actual = ManifestComparer.compare(local, remote, baseline)
        assertEquals(expected, actual)
        assertEquals(expected.mirrored(), ManifestComparer.compare(remote, local, baseline), "peer's view")
        val mine = ManifestComparer.agreedBaseline(local, remote, actual)
        val theirs = ManifestComparer.agreedBaseline(remote, local, actual.mirrored())
        assertTrue(mine.sameContentAs(theirs), "both sides agree on the new baseline: $mine vs $theirs")
    }

    @Test
    fun row1_localChanged_remoteUnchanged_sendsLocal() {
        assertPlan(plan(send = listOf("a.txt")), local = m(e("a.txt", "v2")), remote = base, baseline = base)
    }

    @Test
    fun row2_remoteChanged_localUnchanged_receivesRemote() {
        assertPlan(plan(receive = listOf("a.txt")), local = base, remote = m(e("a.txt", "v2")), baseline = base)
    }

    @Test
    fun row3_bothChangedDifferently_isConflict() {
        assertPlan(
            plan(conflicts = listOf(PlanConflict("a.txt", ConflictKind.BOTH_CHANGED))),
            local = m(e("a.txt", "mine")), remote = m(e("a.txt", "theirs")), baseline = base,
        )
    }

    @Test
    fun row4_bothChangedToIdenticalContents_isNothing() {
        assertPlan(plan(), local = m(e("a.txt", "same", mtime = 5)), remote = m(e("a.txt", "same", mtime = 9)), baseline = base)
    }

    @Test
    fun row5_newOnBothWithDifferentContents_isConflict() {
        assertPlan(
            plan(conflicts = listOf(PlanConflict("n.txt", ConflictKind.BOTH_CHANGED))),
            local = m(e("a.txt", "v1"), e("n.txt", "mine")), remote = m(e("a.txt", "v1"), e("n.txt", "theirs")), baseline = base,
        )
    }

    @Test
    fun row5b_newOnBothWithIdenticalContents_isNothing() {
        assertPlan(plan(), local = m(e("a.txt", "v1"), e("n.txt", "x")), remote = m(e("a.txt", "v1"), e("n.txt", "x")), baseline = base)
    }

    @Test
    fun row6_deletedLocally_unchangedRemotely_deletesOnRemote() {
        assertPlan(plan(delete = listOf(PlanDeletion("a.txt", Side.REMOTE))), local = m(), remote = base, baseline = base)
    }

    @Test
    fun row6b_deletedRemotely_unchangedLocally_deletesHere() {
        assertPlan(plan(delete = listOf(PlanDeletion("a.txt", Side.LOCAL))), local = base, remote = m(), baseline = base)
    }

    @Test
    fun row7_deletedLocally_changedRemotely_isConflictKeepingTheChangedFile() {
        val p = ManifestComparer.compare(m(), m(e("a.txt", "v2")), base)
        assertEquals(plan(conflicts = listOf(PlanConflict("a.txt", ConflictKind.DELETED_LOCAL))), p)
        assertPlan(p, m(), m(e("a.txt", "v2")), base)
        assertEquals(m(e("a.txt", "v2")), ManifestComparer.agreedBaseline(m(), m(e("a.txt", "v2")), p), "the changed file survives")
    }

    @Test
    fun row7b_changedLocally_deletedRemotely_isConflictKeepingTheChangedFile() {
        val p = ManifestComparer.compare(m(e("a.txt", "v2")), m(), base)
        assertEquals(plan(conflicts = listOf(PlanConflict("a.txt", ConflictKind.DELETED_REMOTE))), p)
        assertPlan(p, m(e("a.txt", "v2")), m(), base)
        assertEquals(m(e("a.txt", "v2")), ManifestComparer.agreedBaseline(m(e("a.txt", "v2")), m(), p))
    }

    @Test
    fun row8_firstSync_samePathDifferentContents_isConflict() {
        assertPlan(
            plan(conflicts = listOf(PlanConflict("a.txt", ConflictKind.BOTH_CHANGED))),
            local = m(e("a.txt", "mine")), remote = m(e("a.txt", "theirs")), baseline = null,
        )
    }

    @Test
    fun row8b_firstSync_copiesMissingFilesBothWays() {
        assertPlan(
            plan(send = listOf("only-here.txt"), receive = listOf("only-there.txt")),
            local = m(e("only-here.txt", "1"), e("same.txt", "s")),
            remote = m(e("only-there.txt", "2"), e("same.txt", "s")),
            baseline = null,
        )
    }

    @Test
    fun row8c_firstSync_neverDeletes() {
        // Without a baseline a missing file is new on the other side, not deleted here.
        val p = ManifestComparer.compare(m(e("a.txt", "v1")), m(), null)
        assertEquals(plan(send = listOf("a.txt")), p)
    }

    @Test
    fun newSinceBaselineOnOneSideIsCopied() {
        assertPlan(plan(send = listOf("new.txt")), local = m(e("a.txt", "v1"), e("new.txt", "n")), remote = base, baseline = base)
        assertPlan(plan(receive = listOf("new.txt")), local = base, remote = m(e("a.txt", "v1"), e("new.txt", "n")), baseline = base)
    }

    @Test
    fun deletedOnBothSidesIsNothing() {
        assertPlan(plan(), local = m(), remote = m(), baseline = base)
    }

    @Test
    fun unchangedIsNothing() {
        assertPlan(plan(), local = base, remote = base, baseline = base)
        assertTrue(ManifestComparer.compare(base, base, base).isEmpty)
    }

    @Test
    fun timesAloneAreNotChanges() {
        assertPlan(plan(), local = m(e("a.txt", "v1", mtime = 100)), remote = m(e("a.txt", "v1", mtime = 200)), baseline = base)
    }

    @Test
    fun executableBitIsAChange() {
        assertPlan(plan(send = listOf("a.txt")), local = m(e("a.txt", "v1", executable = true)), remote = base, baseline = base)
    }

    @Test
    fun excludedPathsAreNeitherSentNorDeleted() {
        val baseline = m(e("a.txt", "v1"), e(".env", "K=1"))
        // Here .env is excluded (not allowed, or newly ignored): its absence must not delete it there.
        val p = ManifestComparer.compare(m(e("a.txt", "v1")), m(e("a.txt", "v1"), e(".env", "K=1")), baseline) { it == ".env" }
        assertTrue(p.isEmpty, "$p")
        val q = ManifestComparer.compare(m(e("a.txt", "v1"), e(".env", "K=2")), m(e("a.txt", "v1")), null) { it == ".env" }
        assertTrue(q.isEmpty, "$q")
    }

    @Test
    fun mixedPlanIsSortedAndComplete() {
        val baseline = m(e("keep", "k"), e("gone-there", "g"), e("gone-here", "h"), e("edit-here", "1"), e("edit-there", "1"), e("both", "1"))
        val local = m(e("keep", "k"), e("gone-there", "g"), e("edit-here", "2"), e("edit-there", "1"), e("both", "L"), e("z-new", "z"))
        val remote = m(e("keep", "k"), e("gone-here", "h"), e("edit-here", "1"), e("edit-there", "2"), e("both", "R"))
        val p = ManifestComparer.compare(local, remote, baseline)
        assertEquals(listOf("edit-here", "z-new"), p.send)
        assertEquals(listOf("edit-there"), p.receive)
        assertEquals(listOf("gone-here"), p.deleteThere)
        assertEquals(listOf("gone-there"), p.deleteHere)
        assertEquals(listOf(PlanConflict("both", ConflictKind.BOTH_CHANGED)), p.conflicts)
        val agreed = ManifestComparer.agreedBaseline(local, remote, p)
        assertEquals(listOf("edit-here", "edit-there", "keep", "z-new"), agreed.entries.map { it.path }, "unresolved conflict left out")
        assertEquals("2", agreed["edit-there"]!!.sha256.take(1))
    }
}
