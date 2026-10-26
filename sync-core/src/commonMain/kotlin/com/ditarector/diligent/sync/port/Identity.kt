package com.ditarector.diligent.sync.port

/**
 * This device's long-lived certificate (SPEC A.1): self-signed X.509, ECDSA
 * P-256, valid 20 years, created once. The private key never leaves the
 * platform's secure storage, so only the public certificate crosses this
 * interface; the TLS adapter uses the key directly.
 */
public interface Identity {
    /** The certificate in DER encoding, exactly as presented during TLS. */
    public fun certificateDer(): ByteArray
}
