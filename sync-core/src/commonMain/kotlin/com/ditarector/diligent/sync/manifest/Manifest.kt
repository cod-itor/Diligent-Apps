package com.ditarector.diligent.sync.manifest

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The files of a project as one device sees them (SPEC A.7), keyed by path.
 *
 * Also the baseline: after a successful sync both devices store the agreed
 * manifest for that pair and project (with [toJson], in app data, never
 * inside the project) and pass it to the next comparison.
 */
public class Manifest(entries: Collection<ManifestEntry>) {
    private val byPath: Map<String, ManifestEntry> = entries.associateBy { it.path }.also {
        require(it.size == entries.size) { "duplicate paths in manifest" }
    }

    /** Entries sorted by path, so equal manifests serialise identically. */
    public val entries: List<ManifestEntry> = byPath.values.sortedBy { it.path }

    /** Every path. */
    public val paths: Set<String> get() = byPath.keys

    /** Number of files. */
    public val size: Int get() = byPath.size

    /** The entry for [path], or null. */
    public operator fun get(path: String): ManifestEntry? = byPath[path]

    /** True if [path] is listed. */
    public operator fun contains(path: String): Boolean = path in byPath

    /** Same paths with the same content and mode; times and sizes aside. */
    public fun sameContentAs(other: Manifest): Boolean =
        paths == other.paths && byPath.all { (path, entry) -> entry.sameContentAs(other[path]) }

    /** For storing a baseline. */
    public fun toJson(): String = json.encodeToString(serializer, entries)

    override fun equals(other: Any?): Boolean = other is Manifest && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "Manifest(${entries.size} files)"

    public companion object {
        /** A manifest with no files. */
        public val EMPTY: Manifest = Manifest(emptyList())

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val serializer = ListSerializer(ManifestEntry.serializer())

        /** Reads a manifest written by [toJson]. */
        public fun fromJson(text: String): Manifest = Manifest(json.decodeFromString(serializer, text))
    }
}
