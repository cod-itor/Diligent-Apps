package com.ditarector.diligent.sync.fakes

import com.ditarector.diligent.sync.message.Message
import com.ditarector.diligent.sync.port.Connection
import com.ditarector.diligent.sync.wire.Inbound
import com.ditarector.diligent.sync.wire.InboundReader

/** Records what is sent and decodes it back, as the peer would. */
class FakeConnection(
    override val peerCertificateDer: ByteArray = ByteArray(0),
    override val remoteHost: String = "192.168.1.9",
) : Connection {
    private val reader = InboundReader()
    val sent = mutableListOf<Inbound>()
    var closed = false
    /** Called with each decoded item as it is sent, to wire two parties together. */
    var onSent: ((Inbound) -> Unit)? = null

    override fun send(bytes: ByteArray) {
        check(!closed) { "send after close" }
        reader.feed(bytes).forEach { sent += it; onSent?.invoke(it) }
    }

    override fun close() {
        closed = true
    }

    val messages: List<Message> get() = sent.mapNotNull { (it as? Inbound.Received)?.message }

    fun takeMessages(): List<Message> = messages.also { sent.clear() }
}
