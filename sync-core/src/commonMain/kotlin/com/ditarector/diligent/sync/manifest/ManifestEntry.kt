package com.ditarector.diligent.sync.manifest

import kotlinx.serialization.Serializable

/**
 * One file in a project manifest (SPEC A.7).
 *
 * @property path relative to the project root, separated by `/`, never
 *   starting with `/` and never containing `.` or `..` segments.
 * @property sha256 lowercase hex of the file's SHA-256 (v1 does not name an
 *   encoding; hex is what every platform's tooling prints).
 * @property mtimeMs modification time in milliseconds since the Unix epoch.
 *   Informational only: clocks differ between devices, so comparison uses
 *   [sha256] and [executable], never time.
 */
@Serializable
public data class ManifestEntry(
    val path: String,
    val size: Long,
    val mtimeMs: Long,
    val sha256: String,
    val executable: Boolean = false,
) {
    /** True when [other] has the same content and mode, which is what "changed" means in A.7. */
    public fun sameContentAs(other: ManifestEntry?): Boolean =
        other != null && other.sha256 == sha256 && other.executable == executable
}
