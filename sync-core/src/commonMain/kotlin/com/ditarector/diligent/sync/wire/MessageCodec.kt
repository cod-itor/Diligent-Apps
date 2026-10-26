package com.ditarector.diligent.sync.wire

import com.ditarector.diligent.sync.message.ErrorCodes
import com.ditarector.diligent.sync.message.ErrorMessage
import com.ditarector.diligent.sync.message.FileData
import com.ditarector.diligent.sync.message.Message
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Converts between [Message] values and frames. */
public object MessageCodec {
    /** Every `type` this library understands. */
    public val knownTypes: Set<String> = Message.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()

    /** The JSON object for [message] with [id]; `type` and `id` come first. */
    public fun toJson(id: String, message: Message): JsonObject {
        val body = Frames.json.encodeToJsonElement(Message.serializer(), message).jsonObject
        val out = LinkedHashMap<String, JsonElement>()
        out["type"] = body.getValue("type")
        out["id"] = JsonPrimitive(id)
        body.forEach { (k, v) -> if (k != "type") out[k] = v }
        return JsonObject(out)
    }

    /** One frame for [message]. `file.data` must go through [encodeFileData]. */
    public fun encode(id: String, message: Message): ByteArray {
        require(message !is FileData) { "file.data needs its bytes; use encodeFileData" }
        return Frames.encode(toJson(id, message))
    }

    /** A `file.data` header frame followed by [bytes]. */
    public fun encodeFileData(id: String, header: FileData, bytes: ByteArray): ByteArray =
        Frames.encodeFileData(toJson(id, header), bytes)

    /** Reads a frame produced by [FrameDecoder]. */
    public fun decode(frame: Frame): Inbound {
        val id = frame.id
        val type = frame.type
        if (type !in knownTypes) return Inbound.Unsupported(id, type)
        val message = try {
            Frames.json.decodeFromJsonElement(Message.serializer(), frame.json)
        } catch (e: SerializationException) {
            return Inbound.Invalid(id, type, e.message ?: "unreadable fields")
        } catch (e: IllegalArgumentException) {
            return Inbound.Invalid(id, type, e.message ?: "unreadable fields")
        }
        return when (frame) {
            is Frame.FileData -> Inbound.FileChunk(id, message as FileData, frame.bytes)
            is Frame.Message -> Inbound.Received(id, message)
        }
    }

    /** The answer A.5 requires for an unknown type. */
    public fun unsupportedReply(item: Inbound.Unsupported): ErrorMessage =
        ErrorMessage(ErrorCodes.UNSUPPORTED, message = "unsupported type ${item.type}", re = item.id)
}

/**
 * Bytes in, [Inbound] items out: a [FrameDecoder] and [MessageCodec] for one
 * connection's read loop. A [FrameException] means close the connection.
 */
public class InboundReader(
    maxFrameBytes: Int = Frames.MAX_FRAME_BYTES,
    maxChunkBytes: Int = Frames.MAX_CHUNK_BYTES,
) {
    private val decoder = FrameDecoder(maxFrameBytes, maxChunkBytes)

    /** Feeds bytes from the socket and returns the messages they complete. */
    public fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<Inbound> =
        decoder.feed(bytes, offset, length).map(MessageCodec::decode)
}
