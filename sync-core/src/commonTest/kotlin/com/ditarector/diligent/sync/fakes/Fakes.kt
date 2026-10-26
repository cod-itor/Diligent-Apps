package com.ditarector.diligent.sync.fakes

import com.ditarector.diligent.sync.port.Clock
import com.ditarector.diligent.sync.port.SecureRandomSource
import com.ditarector.diligent.sync.port.Srp
import com.ditarector.diligent.sync.port.SrpChallenge
import com.ditarector.diligent.sync.port.SrpClient
import com.ditarector.diligent.sync.port.SrpProof
import com.ditarector.diligent.sync.port.SrpServer

class FakeClock(var now: Long = 1_000_000) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** Serves queued bytes first, then a deterministic counter stream. */
class FakeRandom : SecureRandomSource {
    val queue = ArrayDeque<Byte>()
    private var counter = 7

    fun enqueue(vararg bytes: Int) {
        bytes.forEach { queue.addLast(it.toByte()) }
    }

    override fun nextBytes(count: Int): ByteArray = ByteArray(count) {
        queue.removeFirstOrNull() ?: (counter++ * 31).toByte()
    }
}

/**
 * Stand-in for SRP-6a with the same observable contract: the proof checks out
 * only when both sides used the same password, and both derive the same key.
 * No security whatsoever; real SRP lives in the platform adapters.
 */
class FakeSrp : Srp {
    var serversCreated = 0
    private var salt = 0

    private fun h(vararg parts: Any): ByteArray =
        TestHashing.sha256(parts.joinToString("|") { if (it is ByteArray) it.joinToString(",") else it.toString() }.encodeToByteArray())

    override fun newServer(password: String): SrpServer {
        serversCreated++
        return object : SrpServer {
            private val s = byteArrayOf((salt++).toByte(), 1, 2)
            private val b = h("B", s)
            private var verified = false

            override fun challenge() = SrpChallenge(s, b)

            override fun verify(a: ByteArray, m1: ByteArray): ByteArray? {
                if (!m1.contentEquals(h("M1", password, s, b, a))) return null
                verified = true
                return h("M2", password, a)
            }

            override fun sessionKey(): ByteArray {
                check(verified)
                return h("K", password, s)
            }
        }
    }

    override fun newClient(password: String): SrpClient = object : SrpClient {
        private var salt: ByteArray? = null
        private val a = h("A", password.length)
        private var verified = false

        override fun respond(salt: ByteArray, b: ByteArray): SrpProof {
            this.salt = salt
            return SrpProof(a, h("M1", password, salt, b, a))
        }

        override fun verifyServer(m2: ByteArray): Boolean = m2.contentEquals(h("M2", password, a)).also { verified = it }

        override fun sessionKey(): ByteArray {
            check(verified)
            return h("K", password, salt!!)
        }
    }
}
