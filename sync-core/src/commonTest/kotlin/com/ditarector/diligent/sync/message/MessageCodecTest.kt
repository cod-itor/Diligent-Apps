package com.ditarector.diligent.sync.message

import com.ditarector.diligent.sync.device.Platform
import com.ditarector.diligent.sync.fakes.FakeConnection
import com.ditarector.diligent.sync.manifest.ConflictKind
import com.ditarector.diligent.sync.manifest.ManifestEntry
import com.ditarector.diligent.sync.manifest.PlanConflict
import com.ditarector.diligent.sync.manifest.PlanDeletion
import com.ditarector.diligent.sync.manifest.Side
import com.ditarector.diligent.sync.manifest.SyncPlan
import com.ditarector.diligent.sync.wire.Frames
import com.ditarector.diligent.sync.wire.Inbound
import com.ditarector.diligent.sync.wire.InboundReader
import com.ditarector.diligent.sync.wire.MessageCodec
import com.ditarector.diligent.sync.wire.Outbox
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageCodecTest {
    private val entry = ManifestEntry("src/app.js", 12, 1_700_000_000_000, "ab".repeat(32), executable = false)

    private val everyMessage: List<Message> = listOf(
        PairRequest("Toto's Phone", Platform.ANDROID, "OKARTTSNY3V46RSWEP4OSIEKEJUALO3N"),
        PairChallenge(salt = "c2FsdA==", b = "Qg=="),
        PairProof(a = "QQ==", m1 = "TTE="),
        PairConfirm(m2 = "TTI="),
        PairBind("OKARTTSNY3V46RSWEP4OSIEKEJUALO3N", "Laptop", Platform.MACOS, "bWFj"),
        PairForget,
        SyncHello("6f1c", "shop"),
        SyncManifest(listOf(entry), allowEnv = true),
        SyncPlanMessage(
            SyncPlan(
                send = listOf("a"),
                receive = listOf("b"),
                delete = listOf(PlanDeletion("c", Side.REMOTE)),
                conflicts = listOf(PlanConflict("d", ConflictKind.BOTH_CHANGED)),
            ),
        ),
        SyncConfirm,
        FileRequest("src/app.js"),
        SyncDone(listOf(entry)),
        SyncCancel("user"),
        RunStart("6f1c", "web"),
        RunStop,
        RunStatus(),
        RunStatus(running = true, profile = "web"),
        RunLog("listening on 3000", LogStream.STDERR),
        RunExit(1),
        RunPreview("192.168.1.5", 50123),
        AiInfo(),
        AiInfo(reachable = true, models = listOf("qwen2.5-coder:7b")),
        AiEndpoint(),
        AiEndpoint("100.64.0.3", 50124),
        ErrorMessage(ErrorCodes.WRONG_CODE, remaining = 3, re = "4"),
    )

    @Test
    fun everyMessageRoundTrips() {
        val reader = InboundReader()
        everyMessage.forEachIndexed { i, message ->
            val item = assertIs<Inbound.Received>(reader.feed(MessageCodec.encode("m$i", message)).single(), "$message")
            assertEquals("m$i", item.id)
            assertEquals(message, item.message)
        }
    }

    @Test
    fun everyTypeOfTheSpecIsKnown() {
        val spec = setOf(
            "pair.request", "pair.challenge", "pair.proof", "pair.confirm", "pair.bind", "pair.forget",
            "sync.hello", "sync.manifest", "sync.plan", "sync.confirm", "file.request", "file.data", "sync.done", "sync.cancel",
            "run.start", "run.stop", "run.status", "run.log", "run.exit", "run.preview",
            "ai.info", "ai.endpoint", "error",
        )
        assertEquals(spec, MessageCodec.knownTypes)
    }

    @Test
    fun wireNamesFollowTheSpec() {
        assertEquals(
            """{"type":"pair.challenge","id":"1","salt":"c2FsdA==","B":"Qg=="}""",
            MessageCodec.toJson("1", PairChallenge("c2FsdA==", "Qg==")).toString(),
        )
        assertEquals("""{"type":"pair.proof","id":"2","A":"QQ==","M1":"TTE="}""", MessageCodec.toJson("2", PairProof("QQ==", "TTE=")).toString())
        assertEquals("""{"type":"pair.confirm","id":"3","M2":"TTI="}""", MessageCodec.toJson("3", PairConfirm("TTI=")).toString())
        assertEquals("""{"type":"sync.confirm","id":"4"}""", MessageCodec.toJson("4", SyncConfirm).toString())
        assertEquals(
            """{"type":"pair.request","id":"5","name":"A","platform":"ios","deviceId":"X"}""",
            MessageCodec.toJson("5", PairRequest("A", Platform.IOS, "X")).toString(),
        )
        assertEquals(
            """{"type":"sync.manifest","id":"6","entries":[{"path":"src/app.js","size":12,"mtimeMs":1700000000000,"sha256":"${"ab".repeat(32)}","executable":false}],"allowEnv":false}""",
            MessageCodec.toJson("6", SyncManifest(listOf(entry))).toString(),
        )
    }

    @Test
    fun unknownTypeIsAnsweredWithUnsupported() {
        val frame = Frames.encode(buildJsonObject { put("type", "sync.telepathy"); put("id", "41") })
        val item = assertIs<Inbound.Unsupported>(InboundReader().feed(frame).single())
        assertEquals("sync.telepathy", item.type)
        val connection = FakeConnection()
        Outbox(connection).replyUnsupported(item)
        val reply = assertIs<ErrorMessage>(connection.messages.single())
        assertEquals("unsupported", reply.code)
        assertEquals("41", reply.re)
    }

    @Test
    fun unsupportedReplyHasTypeAndIdFirst() {
        val json = MessageCodec.toJson("9", MessageCodec.unsupportedReply(Inbound.Unsupported("41", "x")))
        assertEquals(listOf("type", "id", "code"), json.keys.take(3))
        assertEquals("\"error\"", json["type"].toString())
        assertEquals("\"unsupported\"", json["code"].toString())
    }

    @Test
    fun knownTypeWithMissingFieldsIsInvalid() {
        val frame = Frames.encode(buildJsonObject { put("type", "file.request"); put("id", "1") })
        val item = assertIs<Inbound.Invalid>(InboundReader().feed(frame).single())
        assertEquals("file.request", item.type)
        val badPlatform = Frames.encode(buildJsonObject {
            put("type", "pair.request"); put("id", "2"); put("name", "x"); put("platform", "beos"); put("deviceId", "y")
        })
        assertIs<Inbound.Invalid>(InboundReader().feed(badPlatform).single())
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val frame = Frames.encode(buildJsonObject { put("type", "file.request"); put("id", "1"); put("path", "a"); put("future", 1) })
        assertEquals(FileRequest("a"), assertIs<Inbound.Received>(InboundReader().feed(frame).single()).message)
    }

    @Test
    fun fileDataThroughOutboxAndReader() {
        val connection = FakeConnection()
        val outbox = Outbox(connection)
        val bytes = "hello".encodeToByteArray()
        outbox.send(FileRequest("x"))
        outbox.sendFileData(FileData("x", 0, bytes.size), bytes)
        val chunk = assertIs<Inbound.FileChunk>(connection.sent[1])
        assertEquals(FileData("x", 0, 5), chunk.header)
        assertContentEquals(bytes, chunk.bytes)
        assertEquals(listOf("1", "2"), connection.sent.map { it.id })
        assertFailsWith<IllegalArgumentException> { outbox.send(FileData("x", 0, 0)) }
    }

    @Test
    fun onlyPairingAndErrorsAreAllowedBeforePairing() {
        val allowed = everyMessage.filter { it.allowedBeforePairing }.map { MessageCodec.toJson("1", it)["type"].toString().trim('"') }.toSet()
        assertEquals(setOf("pair.request", "pair.challenge", "pair.proof", "pair.confirm", "pair.bind", "error"), allowed)
        assertFalse(SyncHello("p").allowedBeforePairing)
        assertTrue(ErrorMessage("x").allowedBeforePairing)
    }

    @Test
    fun planMirrorsForTheResponder() {
        val plan = SyncPlan(
            send = listOf("a"),
            receive = listOf("b"),
            delete = listOf(PlanDeletion("c", Side.LOCAL), PlanDeletion("d", Side.REMOTE)),
            conflicts = listOf(PlanConflict("e", ConflictKind.DELETED_LOCAL), PlanConflict("f", ConflictKind.BOTH_CHANGED)),
        )
        val m = plan.mirrored()
        assertEquals(listOf("b"), m.send)
        assertEquals(listOf("a"), m.receive)
        assertEquals(listOf("d"), m.deleteHere)
        assertEquals(listOf("c"), m.deleteThere)
        assertEquals(listOf(ConflictKind.DELETED_REMOTE, ConflictKind.BOTH_CHANGED), m.conflicts.map { it.kind })
        assertEquals(plan, m.mirrored())
        assertEquals(plan, SyncPlanMessage(plan).toPlan())
    }
}
