package com.ditarector.diligent.sync.pairing

import kotlin.io.encoding.Base64

/** Byte values in pairing messages are standard base64 with padding. */
internal object Base64Bytes {
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    fun decode(text: String): ByteArray? = try {
        Base64.decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}
