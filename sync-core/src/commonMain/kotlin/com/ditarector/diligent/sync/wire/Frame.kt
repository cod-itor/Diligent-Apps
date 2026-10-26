package com.ditarector.diligent.sync.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One complete unit read off a connection by [FrameDecoder]. Every frame has a
 * `type` and an `id` (SPEC A.5); turning it into a typed message is the job of
 * [MessageCodec], which keeps this layer free of protocol vocabulary beyond
 * the single type that changes how bytes are read, `file.data`.
 */
public sealed class Frame {
    /** The frame's JSON object, exactly as received. */
    public abstract val json: JsonObject

    /** The `type` field, for example `sync.hello`. */
    public val type: String get() = json.stringOrNull("type")!!

    /** The `id` field the sender chose for this message. */
    public val id: String get() = json.stringOrNull("id")!!

    /** A frame that is JSON only. */
    public class Message(override val json: JsonObject) : Frame()

    /** A `file.data` header together with the raw bytes that followed it. */
    public class FileData(override val json: JsonObject, public val bytes: ByteArray) : Frame()
}

/** The wire was not valid diligent-link; the connection cannot continue. */
public open class FrameException(message: String) : Exception(message)

/**
 * A peer announced a frame or chunk larger than the decoder allows. Thrown
 * before anything is allocated for it, so a bad length prefix costs nothing.
 */
public class FrameTooLargeException(public val announced: Long, public val limit: Int) :
    FrameException("frame of $announced bytes exceeds limit of $limit")

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
