package com.ditarector.diligent.sync.wire

import com.ditarector.diligent.sync.message.ErrorMessage
import com.ditarector.diligent.sync.message.FileData
import com.ditarector.diligent.sync.message.Message
import com.ditarector.diligent.sync.port.Connection

/**
 * The sending half of one connection: numbers messages and writes frames.
 *
 * Ids only need to be unique per connection and direction, so a counter is
 * enough. Pairing and sync on the same connection share one outbox so their
 * ids never collide. Not thread-safe, like [Connection.send].
 */
public class Outbox(public val connection: Connection) {
    private var next = 1L

    /** Sends [message] and returns the id it was given. */
    public fun send(message: Message): String {
        val id = nextId()
        connection.send(MessageCodec.encode(id, message))
        return id
    }

    /** Sends a `file.data` [header] and its [bytes]; returns the id. */
    public fun sendFileData(header: FileData, bytes: ByteArray): String {
        val id = nextId()
        connection.send(MessageCodec.encodeFileData(id, header, bytes))
        return id
    }

    /** Answers an unknown message type as A.5 requires. */
    public fun replyUnsupported(item: Inbound.Unsupported): String = send(MessageCodec.unsupportedReply(item))

    /** Answers [item] with an error [code]. */
    public fun replyError(item: Inbound, code: String, message: String? = null): String =
        send(ErrorMessage(code, message, re = item.id))

    private fun nextId(): String = (next++).toString()
}
