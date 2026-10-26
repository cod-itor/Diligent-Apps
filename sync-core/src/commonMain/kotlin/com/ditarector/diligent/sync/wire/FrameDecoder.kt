package com.ditarector.diligent.sync.wire

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * Turns the byte stream of one connection back into [Frame]s.
 *
 * Transports hand over whatever the socket produced, which may be half a
 * length prefix, three frames at once or a frame split across many reads;
 * [feed] buffers until a frame is complete and returns every frame that is.
 *
 * Any [FrameException] means the stream can no longer be trusted (a length is
 * out of range, the JSON is broken or `type`/`id` is missing). The decoder then
 * refuses further input and the caller closes the connection: resynchronising
 * a length-prefixed stream after an error is guesswork.
 *
 * Not thread-safe; one decoder belongs to one connection's read loop.
 */
public class FrameDecoder(
    private val maxFrameBytes: Int = Frames.MAX_FRAME_BYTES,
    private val maxChunkBytes: Int = Frames.MAX_CHUNK_BYTES,
) {
    private var buffer = ByteArray(8 * 1024)
    private var start = 0
    private var end = 0
    private var pendingHeader: JsonObject? = null
    private var pendingLength = 0
    private var failed = false

    /** Bytes received but not yet part of a returned frame. */
    public val bufferedBytes: Int get() = end - start

    /**
     * Adds [length] bytes of [bytes] starting at [offset] and returns the
     * frames they complete, in order. Returns an empty list while a frame is
     * still partial.
     */
    public fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<Frame> {
        if (failed) throw FrameException("decoder already failed; close the connection")
        append(bytes, offset, length)
        val frames = ArrayList<Frame>()
        try {
            while (true) frames += next() ?: break
        } catch (e: FrameException) {
            failed = true
            throw e
        }
        return frames
    }

    private fun next(): Frame? {
        val header = pendingHeader
        if (header != null) {
            if (end - start < pendingLength) return null
            val payload = buffer.copyOfRange(start, start + pendingLength)
            start += pendingLength
            pendingHeader = null
            return Frame.FileData(header, payload)
        }
        if (end - start < Frames.LENGTH_PREFIX_BYTES) return null
        val size = readLength()
        if (size > maxFrameBytes) throw FrameTooLargeException(size, maxFrameBytes)
        if (size == 0L) throw FrameException("empty frame")
        val bodyLength = size.toInt()
        if (end - start < Frames.LENGTH_PREFIX_BYTES + bodyLength) return null
        val bodyStart = start + Frames.LENGTH_PREFIX_BYTES
        val json = parse(bodyStart, bodyStart + bodyLength)
        start = bodyStart + bodyLength
        if (json.stringOrNull("type") != FILE_DATA) return Frame.Message(json)
        val chunk = json.longOrNull("length") ?: throw FrameException("file.data without a numeric length")
        if (chunk < 0) throw FrameException("file.data with negative length")
        if (chunk > maxChunkBytes) throw FrameTooLargeException(chunk, maxChunkBytes)
        pendingHeader = json
        pendingLength = chunk.toInt()
        return next()
    }

    private fun readLength(): Long {
        var value = 0L
        for (i in 0 until Frames.LENGTH_PREFIX_BYTES) value = (value shl 8) or (buffer[start + i].toLong() and 0xFF)
        return value
    }

    private fun parse(from: Int, to: Int): JsonObject {
        val json = try {
            val text = buffer.decodeToString(from, to, throwOnInvalidSequence = true)
            Frames.json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: CharacterCodingException) {
            null
        } ?: throw FrameException("frame is not a JSON object")
        if (json.stringOrNull("type").isNullOrEmpty()) throw FrameException("frame without a type")
        if (json.stringOrNull("id") == null) throw FrameException("frame without an id")
        return json
    }

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range out of bounds" }
        if (start == end) {
            start = 0
            end = 0
        }
        if (end + length > buffer.size) {
            val live = end - start
            val needed = live + length
            if (needed > buffer.size) {
                var size = buffer.size
                while (size < needed) size *= 2
                val grown = ByteArray(size)
                buffer.copyInto(grown, 0, start, end)
                buffer = grown
            } else {
                buffer.copyInto(buffer, 0, start, end)
            }
            start = 0
            end = live
        }
        bytes.copyInto(buffer, end, offset, offset + length)
        end += length
    }

    private companion object {
        const val FILE_DATA = "file.data"
    }
}
