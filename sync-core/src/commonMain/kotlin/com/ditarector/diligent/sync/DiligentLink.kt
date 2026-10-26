package com.ditarector.diligent.sync

/**
 * Constants of the diligent-link protocol that every app must agree on.
 *
 * SPEC.md next to this library is the source of truth; these values are copied
 * from it so code never spells them twice. Changing any of them is a protocol
 * change and needs a new [VERSION].
 */
public object DiligentLink {
    /** Protocol version, advertised as `v` in the DNS-SD TXT record (A.2). */
    public const val VERSION: Int = 1

    /** DNS-SD service type (A.2). */
    public const val SERVICE_TYPE: String = "_diligent._tcp"

    /** Port tried first; any free port is used when it is busy (A.2). */
    public const val DEFAULT_PORT: Int = 47321

    /** Scheme of the manual-pairing QR code `diligent://<ip>:<port>/<device ID>` (A.2). */
    public const val URI_SCHEME: String = "diligent"
}
