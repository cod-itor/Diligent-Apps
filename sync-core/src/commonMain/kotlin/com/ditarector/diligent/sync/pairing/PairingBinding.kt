package com.ditarector.diligent.sync.pairing

import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.port.Hashing

/**
 * The `pair.bind` MAC (SPEC A.6 step 4): HMAC-SHA512 under the SRP session
 * key `K` over the sender's own certificate fingerprint followed by the
 * receiver's, each as the raw 32-byte SHA-256 digest.
 *
 * Only someone who knows the code can compute `K`, and only the two real TLS
 * endpoints see these two certificates, so a man in the middle who relays
 * the SRP exchange still cannot produce a MAC that matches both connections.
 */
public object PairingBinding {
    /** The MAC the sender puts in its own `pair.bind`. */
    public fun mac(hashing: Hashing, sessionKey: ByteArray, own: Fingerprint, peer: Fingerprint): ByteArray =
        hashing.hmacSha512(sessionKey, own.bytes + peer.bytes)

    /**
     * Checks a received `pair.bind`: [sender] MACed its own fingerprint first,
     * then [receiver]'s. Compared in constant time.
     */
    public fun verify(hashing: Hashing, sessionKey: ByteArray, sender: Fingerprint, receiver: Fingerprint, mac: ByteArray): Boolean =
        constantTimeEquals(mac(hashing, sessionKey, sender, receiver), mac)

    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
