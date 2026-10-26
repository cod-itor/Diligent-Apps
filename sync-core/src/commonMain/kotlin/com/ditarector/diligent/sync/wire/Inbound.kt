package com.ditarector.diligent.sync.wire

import com.ditarector.diligent.sync.message.FileData
import com.ditarector.diligent.sync.message.Message

/**
 * A decoded incoming frame. Unknown and invalid messages are values rather
 * than exceptions because the connection survives them: the receiver answers
 * with an error and carries on (A.5).
 */
public sealed class Inbound {
    /** The sender's `id` of this message. */
    public abstract val id: String

    /** A known message. */
    public data class Received(override val id: String, val message: Message) : Inbound()

    /** A `file.data` header and its bytes. */
    public class FileChunk(override val id: String, public val header: FileData, public val bytes: ByteArray) : Inbound() {
        override fun toString(): String = "FileChunk($id, $header)"
    }

    /** A type this library does not know; answer with `error` code `unsupported`. */
    public data class Unsupported(override val id: String, val type: String) : Inbound()

    /** A known type whose fields could not be read. */
    public data class Invalid(override val id: String, val type: String, val reason: String) : Inbound()
}
