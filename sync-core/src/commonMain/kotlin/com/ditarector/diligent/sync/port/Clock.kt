package com.ditarector.diligent.sync.port

/** Wall-clock time, a port so pairing expiry and lockout can be tested without waiting. */
public fun interface Clock {
    /** Milliseconds since the Unix epoch. */
    public fun nowMs(): Long
}
