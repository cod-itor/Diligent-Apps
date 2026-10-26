package com.ditarector.diligent.sync.device

import com.ditarector.diligent.sync.port.Hashing

/**
 * SHA-256 of a device certificate in DER form: what a peer is pinned to after
 * pairing (SPEC A.4) and what `pair.bind` MACs over (A.6).
 *
 * SPEC v1 does not spell out the fingerprint's encoding. sync-core uses the
 * raw 32-byte digest wherever bytes are needed (the `pair.bind` MAC input) and
 * lowercase hex wherever text is needed (the pairing record). Every app goes
 * through this class, so they all agree.
 */
public class Fingerprint private constructor(private val digest: ByteArray) {
    /** The 32 digest bytes; a copy, so callers cannot change the pin. */
    public val bytes: ByteArray get() = digest.copyOf()

    /** Lowercase hex, 64 characters; the form stored in pairing records. */
    public val hex: String = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** The device ID this certificate stands for (SPEC A.1). */
    public val deviceId: DeviceId get() = DeviceId.of(this)

    override fun equals(other: Any?): Boolean = other is Fingerprint && other.digest.contentEquals(digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = hex

    public companion object {
        /** Digest length in bytes. */
        public const val SIZE: Int = 32

        /** The fingerprint of a DER certificate. */
        public fun ofCertificate(certificateDer: ByteArray, hashing: Hashing): Fingerprint =
            ofDigest(hashing.sha256(certificateDer))

        /** Wraps an existing 32-byte SHA-256 digest. */
        public fun ofDigest(digest: ByteArray): Fingerprint {
            require(digest.size == SIZE) { "a SHA-256 digest has $SIZE bytes, not ${digest.size}" }
            return Fingerprint(digest.copyOf())
        }

        /** Parses the 64-character [hex] form; null if it is not one. */
        public fun parseHex(hex: String): Fingerprint? {
            if (hex.length != SIZE * 2) return null
            val out = ByteArray(SIZE)
            for (i in 0 until SIZE) {
                val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
                val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return Fingerprint(out)
        }
    }
}
