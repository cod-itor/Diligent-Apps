package com.ditarector.diligent.sync.port

/**
 * Hash functions the protocol needs, supplied by the platform (for example
 * `MessageDigest` and `Mac` on the JVM, CryptoKit on iOS). sync-core never
 * carries its own cryptography, so a vetted implementation is always used.
 */
public interface Hashing {
    /** SHA-256 of [data]; 32 bytes. Used for device IDs and pinned fingerprints. */
    public fun sha256(data: ByteArray): ByteArray

    /** HMAC-SHA512 of [data] under [key]; 64 bytes. Used by `pair.bind` (SPEC A.6 step 4). */
    public fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray
}
