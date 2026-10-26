package com.ditarector.diligent.sync.device

import com.ditarector.diligent.sync.DiligentLink
import com.ditarector.diligent.sync.port.Endpoint

/**
 * What a device advertises over DNS-SD (SPEC A.2). [toTxt] and [fromTxt] are
 * the single definition of the TXT keys, so the three apps cannot drift.
 *
 * @property acceptsPairing the `pair` key: true while this device accepts
 *   pairing requests (the Devices screen's visibility setting).
 */
public data class ServiceInfo(
    val name: String,
    val platform: Platform,
    val deviceId: DeviceId,
    val acceptsPairing: Boolean,
    val version: Int = DiligentLink.VERSION,
) {
    /** The TXT record: `v`, `name`, `platform`, `id`, `pair`. */
    public fun toTxt(): Map<String, String> = linkedMapOf(
        "v" to version.toString(),
        "name" to name,
        "platform" to platform.wireName,
        "id" to deviceId.value,
        "pair" to if (acceptsPairing) "1" else "0",
    )

    public companion object {
        /**
         * Reads a TXT record. Returns null when a key is missing or invalid,
         * or when `v` is not a version this library speaks, so callers simply
         * skip such advertisements.
         */
        public fun fromTxt(txt: Map<String, String>): ServiceInfo? {
            val version = txt["v"]?.toIntOrNull() ?: return null
            if (version != DiligentLink.VERSION) return null
            return ServiceInfo(
                name = txt["name"]?.takeIf { it.isNotBlank() } ?: return null,
                platform = txt["platform"]?.let { Platform.fromWireName(it) } ?: return null,
                deviceId = txt["id"]?.let { DeviceId.parse(it) } ?: return null,
                acceptsPairing = txt["pair"] == "1",
                version = version,
            )
        }
    }
}

/**
 * The manual fallback (SPEC A.2): an address typed by the user or the QR code
 * `diligent://<ip>:<port>/<device ID>`.
 */
public data class ManualAddress(val endpoint: Endpoint, val deviceId: DeviceId) {
    /** The QR code text. */
    public fun toUri(): String = "${DiligentLink.URI_SCHEME}://$endpoint/${deviceId.value}"

    public companion object {
        /** Parses a QR code URI; IPv6 hosts must be bracketed. Null if it is not one. */
        public fun parse(uri: String): ManualAddress? {
            val prefix = "${DiligentLink.URI_SCHEME}://"
            if (!uri.startsWith(prefix, ignoreCase = true)) return null
            val rest = uri.substring(prefix.length)
            val slash = rest.lastIndexOf('/')
            if (slash < 0) return null
            val deviceId = DeviceId.parse(rest.substring(slash + 1)) ?: return null
            val endpoint = parseEndpoint(rest.substring(0, slash)) ?: return null
            return ManualAddress(endpoint, deviceId)
        }

        /** Parses `host:port` or `[v6]:port`; without a port, [DiligentLink.DEFAULT_PORT] is used. */
        public fun parseEndpoint(text: String): Endpoint? {
            val host: String
            val portText: String?
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return null
                host = text.substring(1, close)
                val after = text.substring(close + 1)
                portText = if (after.isEmpty()) null else after.removePrefix(":").takeIf { after.startsWith(":") } ?: return null
            } else {
                val colon = text.lastIndexOf(':')
                if (colon >= 0 && text.indexOf(':') != colon) return null
                host = if (colon < 0) text else text.substring(0, colon)
                portText = if (colon < 0) null else text.substring(colon + 1)
            }
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' }) return null
            val port = if (portText == null) DiligentLink.DEFAULT_PORT else portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return Endpoint(host, port)
        }
    }
}
