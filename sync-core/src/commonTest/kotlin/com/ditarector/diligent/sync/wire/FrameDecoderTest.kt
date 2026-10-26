package com.ditarector.diligent.sync.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameDecoderTest {
    private fun message(type: String, id: String, extra: Map<String, String> = emptyMap()): JsonObject = buildJsonObject {
        put("type", type)
        put("id", id)
        extra.forEach { (k, v) -> put(k, v) }
    }

    private fun fileHeader(id: String, path: String, offset: Long, length: Int): JsonObject = buildJsonObject {
        put("type", "file.data")
        put("id", id)
        put("path", path)
        put("offset", offset)
        put("length", length)
    }

    private fun prefix(length: Long): ByteArray = byteArrayOf(
        (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
    )

    @Test
    fun encodesBigEndianLengthThenUtf8Json() {
        val bytes = Frames.encode(message("sync.cancel", "7"))
        val body = bytes.copyOfRange(4, bytes.size).decodeToString()
        assertContentEquals(prefix(body.encodeToByteArray().size.toLong()), bytes.copyOfRange(0, 4))
        assertEquals("""{"type":"sync.cancel","id":"7"}""", body)
    }

    @Test
    fun roundTripsOneFrame() {
        val sent = message("sync.hello", "1", mapOf("projectId" to "p"))
        val frames = FrameDecoder().feed(Frames.encode(sent))
        val frame = assertIs<Frame.Message>(frames.single())
        assertEquals(sent, frame.json)
        assertEquals("sync.hello", frame.type)
        assertEquals("1", frame.id)
    }

    @Test
    fun roundTripsSeveralFramesDeliveredTogether() {
        val all = Frames.encode(message("a", "1")) + Frames.encode(message("b", "2")) + Frames.encode(message("c", "3"))
        assertEquals(listOf("a", "b", "c"), FrameDecoder().feed(all).map { it.type })
    }

    @Test
    fun partialReadsByteByByteYieldTheFrameOnlyWhenComplete() {
        val bytes = Frames.encode(message("sync.hello", "1", mapOf("name" to "Grüße 🌍")))
        val decoder = FrameDecoder()
        for (i in 0 until bytes.size - 1) {
            assertTrue(decoder.feed(bytes, i, 1).isEmpty(), "no frame before byte ${i + 1}")
        }
        val frame = decoder.feed(bytes, bytes.size - 1, 1).single()
        assertEquals(JsonPrimitive("Grüße 🌍"), frame.json["name"])
        assertEquals(0, decoder.bufferedBytes)
    }

    @Test
    fun frameSplitAcrossReadsAtArbitraryPoints() {
        val stream = Frames.encode(message("a", "1")) + Frames.encode(message("b", "2"))
        val decoder = FrameDecoder()
        val first = decoder.feed(stream, 0, 3)
        val second = decoder.feed(stream, 3, 20)
        val third = decoder.feed(stream, 23, stream.size - 23)
        assertEquals(listOf("a", "b"), (first + second + third).map { it.type })
    }

    @Test
    fun largeFrameGrowsTheBuffer() {
        val big = "x".repeat(200_000)
        val frame = FrameDecoder().feed(Frames.encode(message("sync.manifest", "1", mapOf("blob" to big)))).single()
        assertEquals(JsonPrimitive(big), frame.json["blob"])
    }

    @Test
    fun oversizeFrameIsRejectedFromItsPrefixAlone() {
        val decoder = FrameDecoder(maxFrameBytes = 100)
        val e = assertFailsWith<FrameTooLargeException> { decoder.feed(prefix(101)) }
        assertEquals(101, e.announced)
        assertEquals(100, e.limit)
    }

    @Test
    fun hugeLengthPrefixIsRejected() {
        assertFailsWith<FrameTooLargeException> { FrameDecoder().feed(byteArrayOf(-1, -1, -1, -1)) }
    }

    @Test
    fun frameAtTheLimitIsAccepted() {
        val bytes = Frames.encode(message("t", "1"))
        val frames = FrameDecoder(maxFrameBytes = bytes.size - 4).feed(bytes)
        assertEquals(1, frames.size)
    }

    @Test
    fun decoderRefusesInputAfterAFailure() {
        val decoder = FrameDecoder(maxFrameBytes = 10)
        assertFailsWith<FrameTooLargeException> { decoder.feed(prefix(11)) }
        assertFailsWith<FrameException> { decoder.feed(Frames.encode(message("t", "1"))) }
    }

    @Test
    fun encoderRefusesOversizeFrame() {
        assertFailsWith<IllegalArgumentException> {
            Frames.encode(message("t", "1", mapOf("blob" to "x".repeat(Frames.MAX_FRAME_BYTES))))
        }
    }

    @Test
    fun fileDataHeaderIsFollowedByRawBytes() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val stream = Frames.encodeFileData(fileHeader("9", "src/a.bin", 0, payload.size), payload) +
            Frames.encode(message("sync.done", "10"))
        val frames = FrameDecoder().feed(stream)
        val data = assertIs<Frame.FileData>(frames[0])
        assertEquals("file.data", data.type)
        assertEquals(JsonPrimitive("src/a.bin"), data.json["path"])
        assertContentEquals(payload, data.bytes)
        assertEquals("sync.done", frames[1].type)
    }

    @Test
    fun fileDataPayloadSplitAcrossReads() {
        val payload = ByteArray(5000) { it.toByte() }
        val stream = Frames.encodeFileData(fileHeader("1", "a", 0, payload.size), payload)
        val decoder = FrameDecoder()
        val headerEnd = stream.size - payload.size
        assertTrue(decoder.feed(stream, 0, headerEnd + 10).isEmpty(), "header alone is not a frame yet")
        assertTrue(decoder.feed(stream, headerEnd + 10, 2000).isEmpty())
        val data = assertIs<Frame.FileData>(decoder.feed(stream, headerEnd + 2010, stream.size - headerEnd - 2010).single())
        assertContentEquals(payload, data.bytes)
    }

    @Test
    fun payloadBytesThatLookLikeAFrameAreNotParsed() {
        val inner = Frames.encode(message("sync.cancel", "x"))
        val stream = Frames.encodeFileData(fileHeader("1", "a", 0, inner.size), inner)
        val data = assertIs<Frame.FileData>(FrameDecoder().feed(stream).single())
        assertContentEquals(inner, data.bytes)
    }

    @Test
    fun zeroLengthFileData() {
        val data = assertIs<Frame.FileData>(FrameDecoder().feed(Frames.encodeFileData(fileHeader("1", "empty", 0, 0), ByteArray(0))).single())
        assertEquals(0, data.bytes.size)
    }

    @Test
    fun oversizeFileChunkIsRejected() {
        val decoder = FrameDecoder(maxChunkBytes = 10)
        assertFailsWith<FrameTooLargeException> { decoder.feed(Frames.encode(fileHeader("1", "a", 0, 11))) }
    }

    @Test
    fun encoderRefusesMismatchedFileDataLength() {
        assertFailsWith<IllegalArgumentException> { Frames.encodeFileData(fileHeader("1", "a", 0, 5), ByteArray(4)) }
    }

    @Test
    fun malformedFramesAreRejected() {
        fun raw(text: String) = text.encodeToByteArray().let { prefix(it.size.toLong()) + it }
        assertFailsWith<FrameException> { FrameDecoder().feed(raw("not json")) }
        assertFailsWith<FrameException> { FrameDecoder().feed(raw("[1,2]")) }
        assertFailsWith<FrameException> { FrameDecoder().feed(raw("""{"id":"1"}""")) }
        assertFailsWith<FrameException> { FrameDecoder().feed(raw("""{"type":"a"}""")) }
        assertFailsWith<FrameException> { FrameDecoder().feed(raw("""{"type":"file.data","id":"1","length":"5"}""")) }
        assertFailsWith<FrameException> { FrameDecoder().feed(prefix(0)) }
        assertFailsWith<FrameException> { FrameDecoder().feed(prefix(2) + byteArrayOf(0xC3.toByte(), 0x28)) }
    }
}
