package com.ditarector.diligent.sync.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Writes diligent-link frames (SPEC A.5): a 4-byte big-endian length, then that
 * many bytes of UTF-8 JSON. A `file.data` header frame is followed directly by
 * its raw bytes, which carry no length prefix of their own; the header's
 * `length` field says how many there are.
 */
public object Frames {
    /** Size of the big-endian length prefix in front of every JSON frame. */
    public const val LENGTH_PREFIX_BYTES: Int = 4

    /**
     * Largest JSON frame a decoder accepts by default. The biggest legitimate
     * frame is a manifest; 16 MiB holds roughly 100 000 entries, far above any
     * project worth syncing by hand, while a hostile or broken peer still
     * cannot make the receiver allocate gigabytes from one length prefix.
     */
    public const val MAX_FRAME_BYTES: Int = 16 * 1024 * 1024

    /**
     * Largest raw payload one `file.data` header may announce. Files larger
     * than this travel as several `file.data` frames with growing `offset`.
     */
    public const val MAX_CHUNK_BYTES: Int = 1024 * 1024

    /** Chunk size senders use; well under [MAX_CHUNK_BYTES] to keep latency low. */
    public const val SEND_CHUNK_BYTES: Int = 256 * 1024

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Encodes one JSON object as a length-prefixed frame. */
    public fun encode(message: JsonObject): ByteArray = encodeText(json.encodeToString(JsonObject.serializer(), message))

    /**
     * Encodes a `file.data` header followed by its raw [bytes]. The header's
     * `length` must equal `bytes.size`; a mismatch would desynchronise the
     * peer's decoder for the rest of the connection, so it fails here instead.
     */
    public fun encodeFileData(header: JsonObject, bytes: ByteArray): ByteArray {
        val announced = header.longOrNull("length")
        require(announced == bytes.size.toLong()) { "file.data length $announced does not match ${bytes.size} payload bytes" }
        require(bytes.size <= MAX_CHUNK_BYTES) { "file.data chunk of ${bytes.size} bytes exceeds $MAX_CHUNK_BYTES" }
        return encode(header) + bytes
    }

    internal fun encodeText(text: String): ByteArray {
        val body = text.encodeToByteArray()
        require(body.size <= MAX_FRAME_BYTES) { "frame of ${body.size} bytes exceeds $MAX_FRAME_BYTES" }
        val out = ByteArray(LENGTH_PREFIX_BYTES + body.size)
        writeLength(out, body.size)
        body.copyInto(out, LENGTH_PREFIX_BYTES)
        return out
    }

    private fun writeLength(out: ByteArray, length: Int) {
        out[0] = (length ushr 24).toByte()
        out[1] = (length ushr 16).toByte()
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
    }
}
