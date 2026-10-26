package com.ditarector.diligent.sync.port

/**
 * Cryptographically secure random bytes (`java.security.SecureRandom`,
 * `SecRandomCopyBytes`). Pairing codes come from here (SPEC A.6 step 2);
 * `kotlin.random.Random` is not good enough for that.
 */
public fun interface SecureRandomSource {
    /** [count] fresh random bytes. */
    public fun nextBytes(count: Int): ByteArray
}
