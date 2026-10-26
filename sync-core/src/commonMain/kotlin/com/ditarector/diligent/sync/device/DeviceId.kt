package com.ditarector.diligent.sync.device

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * A device's stable public name on the wire (SPEC A.1): SHA-256 of its
 * certificate, base32 without padding, first 32 characters (160 bits).
 *
 * Because the ID comes from the certificate, a reinstalled app gets a new ID;
 * the registry spots that through [DeviceRegistry.check] when a peer claims an
 * ID its certificate does not produce.
 *
 * [value] is the canonical 32-character form used in messages, TXT records
 * and QR codes; [display] is the grouped form for people.
 */
@Serializable
@JvmInline
public value class DeviceId private constructor(public val value: String) {
    /** Groups of 4 joined by hyphens, `ABCD-EFGH-...`, for screens and logs. */
    public val display: String get() = value.chunked(GROUP).joinToString("-")

    override fun toString(): String = value

    public companion object {
        /** Characters in a device ID. */
        public const val LENGTH: Int = 32
        private const val GROUP = 4

        /** The ID for [fingerprint]. */
        public fun of(fingerprint: Fingerprint): DeviceId = DeviceId(Base32.encode(fingerprint.bytes).substring(0, LENGTH))

        /**
         * Parses an ID typed by a person or read from a QR code: case and
         * hyphens or spaces between groups do not matter. Null when the rest is
         * not exactly 32 base32 characters.
         */
        public fun parse(text: String): DeviceId? {
            val canonical = text.filterNot { it == '-' || it == ' ' }.uppercase()
            if (canonical.length != LENGTH || canonical.any { it !in Base32.ALPHABET }) return null
            return DeviceId(canonical)
        }
    }
}
