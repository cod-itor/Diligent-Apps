package com.ditarector.diligent.sync.manifest

import com.ditarector.diligent.sync.port.FileStore
import com.ditarector.diligent.sync.port.PathFilter

/** Builds a device's manifest. Apps save open editors first (A.7 Unsaved changes). */
public object ManifestBuilder {
    /**
     * Walks [store], skipping what [rules] ignores, and hashes each file.
     *
     * With [previous] (this device's last manifest of the project), a file
     * whose size and modification time are unchanged reuses the old hash,
     * which keeps rebuilding cheap. The rare stale hash this allows is caught
     * by the receiver's SHA-256 check, so it can fail a sync but never corrupt
     * a file.
     */
    public fun build(store: FileStore, rules: IgnoreRules, previous: Manifest? = null): Manifest {
        val files = store.list(PathFilter { path, isDirectory -> !rules.isIgnored(path, isDirectory) })
        return Manifest(
            files.filter { ProjectPaths.isValid(it.path) && !rules.isIgnored(it.path, false) }.map { stat ->
                val old = previous?.get(stat.path)
                val hash = if (old != null && old.size == stat.size && old.mtimeMs == stat.mtimeMs) old.sha256 else store.sha256(stat.path)
                ManifestEntry(stat.path, stat.size, stat.mtimeMs, hash, stat.executable)
            },
        )
    }
}
