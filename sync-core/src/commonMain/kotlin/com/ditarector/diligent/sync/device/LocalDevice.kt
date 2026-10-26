package com.ditarector.diligent.sync.device

import com.ditarector.diligent.sync.port.Hashing
import com.ditarector.diligent.sync.port.Identity

/**
 * This device as the protocol describes it to peers: its certificate
 * fingerprint and ID plus the user-editable [name] and the [platform].
 */
public data class LocalDevice(
    val fingerprint: Fingerprint,
    val name: String,
    val platform: Platform,
) {
    /** Derived from [fingerprint]; never stored separately so it cannot drift. */
    val deviceId: DeviceId get() = fingerprint.deviceId

    public companion object {
        /** Builds the local device from the platform [identity]. */
        public fun from(identity: Identity, hashing: Hashing, name: String, platform: Platform): LocalDevice =
            LocalDevice(Fingerprint.ofCertificate(identity.certificateDer(), hashing), name, platform)
    }
}
