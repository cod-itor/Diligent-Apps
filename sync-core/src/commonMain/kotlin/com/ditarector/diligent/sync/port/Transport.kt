package com.ditarector.diligent.sync.port

import com.ditarector.diligent.sync.device.Fingerprint

/**
 * TCP with mutual TLS 1.3 (SPEC A.4), implemented by each app (`SSLSocket` on
 * Android and desktop, Network.framework on iOS, `SSLEngine` over Nearby
 * Connections). Both sides present their [Identity] certificate.
 *
 * The adapter never parses diligent-link; it moves bytes. Policy stays in
 * common code: after the handshake the app checks
 * [com.ditarector.diligent.sync.device.DeviceRegistry.check] on the peer's
 * certificate, and an unpaired peer may only exchange messages for which
 * [com.ditarector.diligent.sync.message.Message.allowedBeforePairing] holds.
 */
public interface Transport {
    /**
     * Connects to [endpoint]. With [pinned] set, the handshake fails unless
     * the peer presents exactly that certificate; with null, any certificate
     * is accepted, which is only for pairing.
     */
    public fun connect(endpoint: Endpoint, pinned: Fingerprint?, handler: ConnectionHandler): Connection

    /**
     * Listens on [port] (0 for any free port) and returns the bound port, to
     * advertise over DNS-SD. Incoming handshakes accept any certificate; the
     * pin is checked by the app as described above.
     */
    public fun listen(port: Int, acceptor: ConnectionAcceptor): Int

    /** Stops accepting connections; open ones stay open. */
    public fun stopListening()
}

/** Decides who reads an accepted connection. */
public fun interface ConnectionAcceptor {
    /** Returns the handler for [connection]'s incoming bytes. */
    public fun accept(connection: Connection): ConnectionHandler
}

/** One established TLS connection. */
public interface Connection {
    /** The certificate the peer presented in the handshake, DER encoded. */
    public val peerCertificateDer: ByteArray

    /** The peer's IP address, for the preview proxy's allow list (A.8). */
    public val remoteHost: String

    /** Writes [bytes] in order. Called from one thread at a time. */
    public fun send(bytes: ByteArray)

    /** Closes the connection; [ConnectionHandler.onClosed] follows. */
    public fun close()
}

/** Receives a connection's bytes, normally by feeding an [com.ditarector.diligent.sync.wire.InboundReader]. */
public interface ConnectionHandler {
    /** Bytes as the socket produced them, in any split. */
    public fun onBytes(bytes: ByteArray)

    /** The connection ended; [error] is null for an orderly close. */
    public fun onClosed(error: Throwable?)
}
