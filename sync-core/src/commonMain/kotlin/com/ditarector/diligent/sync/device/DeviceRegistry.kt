package com.ditarector.diligent.sync.device

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The set of paired devices and the checks that keep pins honest.
 *
 * The registry is an in-memory model; apps persist it with [toJson] after each
 * change and restore it with [fromJson]. It is not thread-safe: apps keep one
 * instance behind their own lock or on one thread.
 */
public class DeviceRegistry(records: Collection<PairingRecord> = emptyList()) {
    private val byId = LinkedHashMap<DeviceId, PairingRecord>().apply { records.forEach { put(it.deviceId, it) } }

    /** Every paired device, in pairing order. */
    public val records: List<PairingRecord> get() = byId.values.toList()

    /** The record for [deviceId], or null if that device is not paired. */
    public operator fun get(deviceId: DeviceId): PairingRecord? = byId[deviceId]

    /**
     * Stores [record] after a successful pairing. Pairing again with the same
     * device replaces the old record, which is how a deliberate re-pair after
     * an identity change takes effect.
     */
    public fun add(record: PairingRecord) {
        require(Fingerprint.parseHex(record.fingerprint)?.deviceId == record.deviceId) {
            "record fingerprint does not produce device ID ${record.deviceId}"
        }
        byId[record.deviceId] = record
    }

    /** Applies [change] to the record for [deviceId] (rename, approval, last seen). Returns the new record. */
    public fun update(deviceId: DeviceId, change: (PairingRecord) -> PairingRecord): PairingRecord? {
        val old = byId[deviceId] ?: return null
        val new = change(old)
        require(new.deviceId == old.deviceId && new.fingerprint == old.fingerprint) { "update cannot change identity; pair again" }
        byId[deviceId] = new
        return new
    }

    /**
     * Forgets [deviceId] and returns the removed record, so the caller can send
     * `pair.forget` to the peer when it is reachable (A.6).
     */
    public fun forget(deviceId: DeviceId): PairingRecord? = byId.remove(deviceId)

    /** True if [fingerprint] belongs to a paired device; the TLS trust check after pairing (A.4). */
    public fun isPinned(fingerprint: Fingerprint): Boolean = byId[fingerprint.deviceId]?.fingerprint == fingerprint.hex

    /**
     * Classifies a connected peer. [presented] is the fingerprint of the
     * certificate the peer showed in TLS; [claimed] is the ID it was reached
     * under (from DNS-SD, a QR code or a stored record), if any.
     *
     * A reinstalled device has a new certificate and so a new ID; the only
     * sign of it is that it answers at an ID it can no longer prove. That case
     * is [PeerCheck.IdentityChanged], which apps show as "This device's
     * identity changed. Pair again only if you reinstalled it."
     */
    public fun check(presented: Fingerprint, claimed: DeviceId? = null): PeerCheck {
        val own = byId[presented.deviceId]
        if (own != null) {
            return if (own.fingerprint == presented.hex) PeerCheck.Paired(own) else PeerCheck.IdentityChanged(own, presented)
        }
        if (claimed != null && claimed != presented.deviceId) {
            byId[claimed]?.let { return PeerCheck.IdentityChanged(it, presented) }
        }
        return PeerCheck.Unpaired(presented)
    }

    /** Serialises every record for storage in app data. */
    public fun toJson(): String = json.encodeToString(serializer, records)

    public companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val serializer = ListSerializer(PairingRecord.serializer())

        /** Restores a registry written by [toJson]. */
        public fun fromJson(text: String): DeviceRegistry = DeviceRegistry(json.decodeFromString(serializer, text))
    }
}

/** Result of [DeviceRegistry.check]. */
public sealed class PeerCheck {
    /** A paired peer with its pinned certificate; full protocol allowed. */
    public data class Paired(val record: PairingRecord) : PeerCheck()

    /** An unknown peer; only pairing messages are allowed (A.4). */
    public data class Unpaired(val presented: Fingerprint) : PeerCheck()

    /** A known device showed a different certificate; refuse and warn (A.4). */
    public data class IdentityChanged(val record: PairingRecord, val presented: Fingerprint) : PeerCheck()
}
