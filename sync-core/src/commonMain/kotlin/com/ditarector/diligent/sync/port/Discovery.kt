package com.ditarector.diligent.sync.port

import com.ditarector.diligent.sync.device.DeviceId
import com.ditarector.diligent.sync.device.ServiceInfo

/**
 * DNS-SD on the local network (SPEC A.2), service type
 * [com.ditarector.diligent.sync.DiligentLink.SERVICE_TYPE]. Implemented with
 * `NsdManager` on Android, `NWBrowser`/`NWListener` on iOS and the OS resolver
 * on desktop. TXT records are built and parsed by [ServiceInfo] so that every
 * app writes the same keys.
 */
public interface Discovery {
    /** Starts or updates the advertisement of this device. */
    public fun advertise(info: ServiceInfo)

    /** Stops advertising; the device then looks offline to unpaired peers. */
    public fun stopAdvertising()

    /** Starts browsing; [listener] hears about peers until [stopBrowsing]. */
    public fun browse(listener: DiscoveryListener)

    /** Stops browsing. */
    public fun stopBrowsing()
}

/** Receives browse results from [Discovery]. Callbacks may arrive on any thread. */
public interface DiscoveryListener {
    /** A peer appeared or changed its TXT record. */
    public fun onFound(peer: DiscoveredPeer)

    /** A previously found peer stopped advertising. */
    public fun onLost(deviceId: DeviceId)
}

/** A resolved advertisement: what the TXT record says plus where to connect. */
public data class DiscoveredPeer(val info: ServiceInfo, val endpoint: Endpoint)

/** A host (IP address or name) and TCP port to connect to. */
public data class Endpoint(val host: String, val port: Int) {
    init {
        require(host.isNotEmpty()) { "empty host" }
        require(port in 1..65535) { "port $port out of range" }
    }

    /** `host:port`, with IPv6 hosts in brackets. */
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}
