package com.ditarector.diligent.sync.port

/**
 * Takes a restorable snapshot of a project before a sync changes it (SPEC
 * A.7 Safety), the same snapshots Diligent's Undo already uses. A sync never
 * writes or deletes a file on a device before that device's snapshot exists.
 */
public interface Snapshotter {
    /**
     * Snapshots the project and returns an id the app can offer as Undo.
     * Throwing aborts the sync before anything is changed.
     */
    public fun snapshot(projectId: String, label: String): String
}
