package me.haroldmartin.codexeink.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEnvelopeCodecTest {
    private val codec = RelayEnvelopeCodec()

    @Test
    fun `encodes controller message with official flattened envelope keys`() {
        val encoded = codec.encode(
            RelayFrame.Message(
                clientId = "client-a",
                streamId = "stream-a",
                seqId = 9,
                cursor = "cursor-a",
                direction = RelayDirection.CLIENT_TO_SERVER,
                message = JsonRpcNotification(method = "initialized"),
            ),
        )

        assertEquals(
            "{\"type\":\"client_message\",\"client_id\":\"client-a\",\"stream_id\":\"stream-a\",\"seq_id\":9,\"cursor\":\"cursor-a\",\"message\":{\"method\":\"initialized\"}}",
            encoded,
        )
    }

    @Test
    fun `decodes host message as server to client`() {
        val decoded = codec.decode(
            """{"type":"server_message","client_id":"c","stream_id":"s","seq_id":4,"message":{"id":2,"result":{}}}""",
        ) as RelayFrame.Message

        assertEquals(RelayDirection.SERVER_TO_CLIENT, decoded.direction)
        assertEquals(JsonRpcId.NumberId(2), (decoded.message as JsonRpcResponse).id)
    }

    @Test
    fun `round trips segmented frame and cumulative ack`() {
        val chunk = RelayFrame.MessageChunk(
            clientId = "c",
            streamId = "s",
            seqId = 10,
            cursor = "next",
            direction = RelayDirection.CLIENT_TO_SERVER,
            segmentId = 2,
            segmentCount = 4,
            messageSizeBytes = 1000,
            messageChunkBase64 = "e30=",
        )
        assertEquals(chunk, codec.decode(codec.encode(chunk)))

        val ack = codec.decode(
            """{"type":"ack","client_id":"c","stream_id":"s","seq_id":10,"segment_id":2}""",
        ) as RelayFrame.Ack
        assertEquals(RelayDirection.SERVER_TO_CLIENT, ack.direction)
        assertEquals(2, ack.segmentId)
    }

    @Test
    fun `decodes ping pong and unknown forward-compatible event`() {
        val ping = codec.decode("""{"type":"ping","client_id":"c"}""") as RelayFrame.Ping
        assertNull(ping.streamId)

        val pong = codec.decode(
            """{"type":"pong","client_id":"c","stream_id":"s","seq_id":5,"status":"active"}""",
        ) as RelayFrame.Pong
        assertEquals(RelayPongStatus.ACTIVE, pong.status)

        val unknown = codec.decode(
            """{"type":"future_event","client_id":"c","stream_id":"s","seq_id":5,"future":true}""",
        ) as RelayFrame.Unknown
        assertEquals(JsonPrimitive(true), unknown.raw["future"])
        assertTrue(codec.encode(unknown).contains("\"future\":true"))
        assertFalse(codec.encode(unknown).contains("null"))
    }

    @Test
    fun `message retains unknown nested json rpc fields`() {
        val decoded = codec.decode(
            """{"type":"server_message","client_id":"c","stream_id":"s","seq_id":1,"message":{"method":"future/event","params":{},"newField":{"x":1}}}""",
        ) as RelayFrame.Message
        val message = decoded.message as JsonRpcNotification

        assertEquals(buildJsonObject { put("x", 1) }, message.raw?.get("newField"))
    }

    @Test
    fun `replay buffer applies cumulative sequence and segment acknowledgements`() {
        val inline = RelayFrame.Message(
            clientId = "c",
            streamId = "s",
            seqId = 1,
            direction = RelayDirection.CLIENT_TO_SERVER,
            message = JsonRpcNotification("initialized"),
        )
        val large = RelayFrame.Message(
            clientId = "c",
            streamId = "s",
            seqId = 2,
            direction = RelayDirection.CLIENT_TO_SERVER,
            message = JsonRpcNotification("event", buildJsonObject { put("text", "x".repeat(100)) }),
        )
        val chunks = ChunkAssembler.segment(large, targetSegmentBytes = 40)
        val buffer = RelayReplayBuffer(codec)
        assertTrue(buffer.record(listOf(inline)))
        assertTrue(buffer.record(chunks))
        assertEquals(2, buffer.pendingSequenceCount())

        buffer.acknowledge(
            RelayFrame.Ack("c", "s", 2, direction = RelayDirection.SERVER_TO_CLIENT, segmentId = 0),
        )
        assertEquals(1, buffer.pendingSequenceCount())
        val remaining = buffer.replayFrames("latest")
        assertTrue(remaining.all { it.seqId == 2L && it.cursor == "latest" })
        assertTrue(remaining.filterIsInstance<RelayFrame.MessageChunk>().all { it.segmentId > 0 })

        buffer.acknowledge(
            RelayFrame.Ack("c", "s", 2, direction = RelayDirection.SERVER_TO_CLIENT),
        )
        assertTrue(buffer.replayFrames(null).isEmpty())
    }

    @Test
    fun `replay buffer is bounded`() {
        val buffer = RelayReplayBuffer(codec, maxPendingSequences = 1, maxPendingBytes = 10_000)
        val first = RelayFrame.Message(
            clientId = "c",
            streamId = "s",
            seqId = 1,
            direction = RelayDirection.CLIENT_TO_SERVER,
            message = JsonRpcNotification("one"),
        )
        assertTrue(buffer.record(listOf(first)))
        assertFalse(buffer.record(listOf(first.copy(seqId = 2, message = JsonRpcNotification("two")))))
    }
}
