package com.ditarector.diligent.sync.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a device remembers about a peer it paired with (SPEC A.6). Apps store
 * these in app data through [DeviceRegistry.toJson]; nothing goes to a server.
 *
 * @property fingerprint the pinned certificate fingerprint, as [Fingerprint.hex].
 * @property pairedAt when pairing succeeded; like [lastSeen] and [lastSync], in
 *   milliseconds since the Unix epoch.
 * @property approval whether this peer may sync without asking (A.7 Approval).
 */
@Serializable
public data class PairingRecord(
    val deviceId: DeviceId,
    val name: String,
    val platform: Platform,
    val fingerprint: String,
    val pairedAt: Long,
    val approval: Approval = Approval.ASK,
    val lastSeen: Long? = null,
    val lastSync: Long? = null,
    val lastAddresses: LastAddresses = LastAddresses(),
) {
    /** [fingerprint] parsed; null only if the stored record was corrupted. */
    val pinnedFingerprint: Fingerprint? get() = Fingerprint.parseHex(fingerprint)
}

/** Where the peer was last reached, tried first next time (A.6). Values are `host:port`. */
@Serializable
public data class LastAddresses(val lan: String? = null, val tailscale: String? = null)

/**
 * The per-peer approval setting. SPEC v1 does not name a default; sync-core
 * starts new pairings at [ASK] so that a freshly paired device cannot pull a
 * project before its owner has seen one request.
 */
@Serializable
public enum class Approval {
    /** Sync requests from this peer are answered without a prompt. */
    @SerialName("always") ALWAYS,

    /** "<Device> wants to sync <project>. Allow?" is shown first. */
    @SerialName("ask") ASK,
}
