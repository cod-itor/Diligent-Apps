package com.ditarector.diligent.sync.pairing

import com.ditarector.diligent.sync.port.SecureRandomSource

/**
 * The 6-digit code the target shows and the requester types (SPEC A.6).
 * It is the SRP password, so it never travels over the wire.
 */
public class PairingCode private constructor(public val digits: String) {
    /** `482 913`: two groups of three, easier to read aloud and to type. */
    public val display: String get() = digits.substring(0, 3) + " " + digits.substring(3)

    override fun equals(other: Any?): Boolean = other is PairingCode && other.digits == digits

    override fun hashCode(): Int = digits.hashCode()

    /** Hidden on purpose so a code does not end up in logs. */
    override fun toString(): String = "PairingCode(******)"

    public companion object {
        /** Number of digits. */
        public const val LENGTH: Int = 6
        private const val RANGE = 1_000_000L

        // Largest multiple of RANGE below 2^32; values at or above it are drawn
        // again so every code is equally likely (no modulo bias).
        private const val LIMIT = (1L shl 32) / RANGE * RANGE

        /** A uniformly random code from [random]. */
        public fun generate(random: SecureRandomSource): PairingCode {
            while (true) {
                val bytes = random.nextBytes(4)
                var value = 0L
                for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
                if (value < LIMIT) return PairingCode((value % RANGE).toString().padStart(LENGTH, '0'))
            }
        }

        /** Reads what a person typed: spaces and hyphens are ignored. Null unless 6 digits remain. */
        public fun parse(input: String): PairingCode? {
            val digits = input.filterNot { it == ' ' || it == '-' }
            return if (digits.length == LENGTH && digits.all { it in '0'..'9' }) PairingCode(digits) else null
        }
    }
}
