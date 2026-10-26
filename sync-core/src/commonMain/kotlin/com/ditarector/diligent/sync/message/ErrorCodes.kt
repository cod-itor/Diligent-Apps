package com.ditarector.diligent.sync.message

/**
 * Values of [ErrorMessage.code]. Only [UNSUPPORTED] is named by SPEC v1; the
 * others are sync-core's, shared so all three apps show the same outcomes.
 */
public object ErrorCodes {
    /** The message type is unknown to the receiver (A.5). */
    public const val UNSUPPORTED: String = "unsupported"

    /** The message type is known but its fields are missing or invalid. */
    public const val INVALID: String = "invalid"

    /** The message is not expected now, or not allowed before pairing. */
    public const val UNEXPECTED: String = "unexpected"

    /** Pairing: the code was wrong; see [ErrorMessage.remaining]. */
    public const val WRONG_CODE: String = "wrong_code"

    /** Pairing: the code is older than 120 seconds. */
    public const val EXPIRED: String = "expired"

    /** Pairing: too many wrong codes; see [ErrorMessage.retryAfterMs]. */
    public const val LOCKED: String = "locked"

    /** Pairing: a newer request replaced this one, or the user dismissed it. */
    public const val CANCELLED: String = "cancelled"

    /** Pairing: no code is pending (never requested, expired, or already used). */
    public const val NO_PENDING: String = "no_pending"

    /** Pairing: the device ID in a message does not match the TLS certificate. */
    public const val IDENTITY_MISMATCH: String = "identity_mismatch"

    /** Pairing: the `pair.bind` MAC did not verify (possible man in the middle). */
    public const val BIND_FAILED: String = "bind_failed"

    /** Pairing: the target does not accept pairing requests right now. */
    public const val NOT_ACCEPTING: String = "not_accepting"

    /** Sync: the user declined the request. */
    public const val DENIED: String = "denied"

    /** Sync: the project is not known here. */
    public const val UNKNOWN_PROJECT: String = "unknown_project"

    /** Sync: a received file did not match its manifest SHA-256. */
    public const val HASH_MISMATCH: String = "hash_mismatch"

    /** Sync: a requested file is not part of the plan or cannot be read. */
    public const val NOT_AVAILABLE: String = "not_available"
}
