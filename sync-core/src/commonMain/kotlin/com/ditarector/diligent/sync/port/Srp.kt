package com.ditarector.diligent.sync.port

/**
 * SRP-6a as SPEC A.6 fixes it: RFC 5054 3072-bit group, SHA-512, username
 * [USERNAME], password = the 6 code digits. Implemented by the platforms
 * (BouncyCastle `SRP6Server`/`SRP6Client` on the JVM, a maintained Swift
 * library on iOS); sync-core only moves the values between the two sides.
 * Byte arrays are unsigned big-endian numbers or raw digests, as the RFC has
 * them.
 */
public interface Srp {
    /** A fresh server (target) session for [password]; one per challenge. */
    public fun newServer(password: String): SrpServer

    /** A fresh client (requester) session for [password]; one per attempt. */
    public fun newClient(password: String): SrpClient

    public companion object {
        /** The SRP username both sides use. */
        public const val USERNAME: String = "diligent"
    }
}

/** The target's half of one SRP-6a exchange. */
public interface SrpServer {
    /** Creates the verifier and returns the salt and the server value `B`. */
    public fun challenge(): SrpChallenge

    /**
     * Checks the client's `A` and proof `M1`. Returns the server proof `M2`
     * when `M1` is right, null when the code was wrong.
     */
    public fun verify(a: ByteArray, m1: ByteArray): ByteArray?

    /** The shared key `K`, available after [verify] succeeded. */
    public fun sessionKey(): ByteArray
}

/** The requester's half of one SRP-6a exchange. */
public interface SrpClient {
    /** Computes `A` and the proof `M1` from the target's [salt] and [b]. */
    public fun respond(salt: ByteArray, b: ByteArray): SrpProof

    /** True when the target's `M2` proves it knew the code too. */
    public fun verifyServer(m2: ByteArray): Boolean

    /** The shared key `K`, available after [verifyServer] returned true. */
    public fun sessionKey(): ByteArray
}

/** Salt and server value `B`, sent in `pair.challenge`. */
public class SrpChallenge(public val salt: ByteArray, public val b: ByteArray)

/** Client value `A` and proof `M1`, sent in `pair.proof`. */
public class SrpProof(public val a: ByteArray, public val m1: ByteArray)
