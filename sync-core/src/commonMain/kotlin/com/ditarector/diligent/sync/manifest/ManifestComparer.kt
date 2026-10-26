package com.ditarector.diligent.sync.manifest

/**
 * The comparison table of SPEC A.7, local L against remote R with the last
 * agreed baseline B:
 *
 * | Situation                                   | Action                                   |
 * | ------------------------------------------- | ---------------------------------------- |
 * | L changed since B, R unchanged              | send L                                   |
 * | R changed since B, L unchanged              | receive R                                |
 * | both changed, contents differ               | conflict ([ConflictKind.BOTH_CHANGED])   |
 * | both changed to identical contents          | nothing                                  |
 * | new on both sides, contents differ          | conflict ([ConflictKind.BOTH_CHANGED])   |
 * | deleted on one side, unchanged on the other | delete on the other side                 |
 * | deleted on one side, changed on the other   | conflict, keep the changed file          |
 * | no baseline (first sync)                    | same path, different contents: conflict; |
 * |                                             | otherwise copy missing files both ways   |
 *
 * "Changed" compares SHA-256 and the executable bit, never times, since the
 * two devices' clocks differ. Creating a file counts as changing it.
 */
public object ManifestComparer {
    /**
     * Computes the plan from the local device's point of view.
     *
     * [excluded] paths are treated as absent from all three manifests: paths
     * this device ignores, and `.env*` files unless both devices allow them.
     * Dropping them from the baseline too is what keeps a newly ignored file
     * from being read as deleted and removed on the other device.
     */
    public fun compare(
        local: Manifest,
        remote: Manifest,
        baseline: Manifest?,
        excluded: (String) -> Boolean = { false },
    ): SyncPlan {
        val send = ArrayList<String>()
        val receive = ArrayList<String>()
        val delete = ArrayList<PlanDeletion>()
        val conflicts = ArrayList<PlanConflict>()
        val paths = (local.paths + remote.paths + (baseline?.paths ?: emptySet())).filterNot(excluded).sorted()
        for (path in paths) {
            val l = local[path]
            val r = remote[path]
            if (baseline == null) {
                when {
                    l != null && r != null -> if (!l.sameContentAs(r)) conflicts += PlanConflict(path, ConflictKind.BOTH_CHANGED)
                    l != null -> send += path
                    r != null -> receive += path
                }
                continue
            }
            val b = baseline[path]
            val localChanged = if (l == null) b != null else !l.sameContentAs(b)
            val remoteChanged = if (r == null) b != null else !r.sameContentAs(b)
            when {
                !localChanged && !remoteChanged -> Unit
                localChanged && !remoteChanged -> if (l != null) send += path else delete += PlanDeletion(path, Side.REMOTE)
                !localChanged && remoteChanged -> if (r != null) receive += path else delete += PlanDeletion(path, Side.LOCAL)
                // Both changed from here on.
                l != null && r != null -> if (!l.sameContentAs(r)) conflicts += PlanConflict(path, ConflictKind.BOTH_CHANGED)
                l == null && r != null -> conflicts += PlanConflict(path, ConflictKind.DELETED_LOCAL)
                l != null && r == null -> conflicts += PlanConflict(path, ConflictKind.DELETED_REMOTE)
                else -> Unit // deleted on both sides
            }
        }
        return SyncPlan(send, receive, delete, conflicts)
    }

    /**
     * The manifest both devices hold once [plan] is applied: the baseline
     * for next time. Conflicts where both versions survive are left out, so
     * the next sync reports them again instead of silently overwriting one
     * side; a conflict with a deletion keeps the changed file, which then
     * exists on both sides.
     */
    public fun agreedBaseline(
        local: Manifest,
        remote: Manifest,
        plan: SyncPlan,
        excluded: (String) -> Boolean = { false },
    ): Manifest {
        val dropped = plan.delete.map { it.path }.toSet() +
            plan.conflicts.filter { it.kind == ConflictKind.BOTH_CHANGED }.map { it.path }
        val fromRemote = plan.receive.toSet() + plan.conflicts.filter { it.kind == ConflictKind.DELETED_LOCAL }.map { it.path }
        val fromLocal = plan.send.toSet() + plan.conflicts.filter { it.kind == ConflictKind.DELETED_REMOTE }.map { it.path }
        val result = ArrayList<ManifestEntry>()
        for (path in (local.paths + remote.paths).filterNot(excluded)) {
            val entry = when (path) {
                in dropped -> null
                in fromRemote -> remote[path]
                in fromLocal -> local[path]
                else -> local[path]?.takeIf { it.sameContentAs(remote[path]) }
            }
            if (entry != null) result += entry
        }
        return Manifest(result)
    }
}
