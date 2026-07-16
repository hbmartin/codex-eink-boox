package me.haroldmartin.codexeink.protocol

import java.util.Base64
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkAssemblerTest {
    @Test
    fun `reassembles unicode payload arriving out of order`() {
        val request = JsonRpcRequest(
            id = JsonRpcId.NumberId(1),
            method = "turn/start",
            params = kotlinx.serialization.json.buildJsonObject {
                put("text", "e-ink ".repeat(100) + "✓")
            },
        )
        val original = RelayFrame.Message(
            clientId = "c",
            streamId = "s",
            seqId = 12,
            direction = RelayDirection.SERVER_TO_CLIENT,
            message = request,
        )
        val chunks = ChunkAssembler.segment(original, targetSegmentBytes = 37)
        val assembler = ChunkAssembler()

        var complete: ChunkAssemblyResult.Complete? = null
        chunks.reversed().forEach { chunk ->
            val result = assembler.offer(chunk)
            if (result is ChunkAssemblyResult.Complete) complete = result
        }

        assertTrue(chunks.size > 2)
        val decoded = JsonRpcCodec().decode(requireNotNull(complete).messageJson) as JsonRpcRequest
        assertEquals(request, decoded.copy(raw = null))
    }

    @Test
    fun `treats exact replay as duplicate and rejects changed replay`() {
        val bytes = "{}".toByteArray()
        val chunk = RelayFrame.MessageChunk(
            clientId = "c",
            streamId = "s",
            seqId = 1,
            direction = RelayDirection.SERVER_TO_CLIENT,
            segmentId = 0,
            segmentCount = 2,
            messageSizeBytes = 4,
            messageChunkBase64 = Base64.getEncoder().encodeToString(bytes),
        )
        val assembler = ChunkAssembler()

        assertEquals(ChunkAssemblyResult.Pending, assembler.offer(chunk))
        assertEquals(ChunkAssemblyResult.Duplicate, assembler.offer(chunk))
        val changed = chunk.copy(messageChunkBase64 = Base64.getEncoder().encodeToString("[]".toByteArray()))
        assertTrue(assembler.offer(changed) is ChunkAssemblyResult.Rejected)
    }

    @Test
    fun `rejects unbounded malformed and mismatched chunks`() {
        val base = RelayFrame.MessageChunk(
            clientId = "c",
            streamId = "s",
            seqId = 1,
            direction = RelayDirection.SERVER_TO_CLIENT,
            segmentId = 0,
            segmentCount = 1,
            messageSizeBytes = 2,
            messageChunkBase64 = "e30=",
        )

        assertTrue(ChunkAssembler().offer(base.copy(streamId = null)) is ChunkAssemblyResult.Rejected)
        assertTrue(ChunkAssembler().offer(base.copy(segmentCount = 0)) is ChunkAssemblyResult.Rejected)
        assertTrue(ChunkAssembler().offer(base.copy(segmentId = 1)) is ChunkAssemblyResult.Rejected)
        assertTrue(ChunkAssembler().offer(base.copy(messageChunkBase64 = "not base64")) is ChunkAssemblyResult.Rejected)
        assertTrue(ChunkAssembler(maxMessageBytes = 1).offer(base) is ChunkAssemblyResult.Rejected)
    }

    @Test
    fun `rejects an incomplete assembly as soon as buffered bytes exceed declared size`() {
        val first = RelayFrame.MessageChunk(
            clientId = "c",
            streamId = "s",
            seqId = 1,
            direction = RelayDirection.SERVER_TO_CLIENT,
            segmentId = 0,
            segmentCount = 3,
            messageSizeBytes = 3,
            messageChunkBase64 = Base64.getEncoder().encodeToString("ab".toByteArray()),
        )
        val second = first.copy(
            segmentId = 1,
            messageChunkBase64 = Base64.getEncoder().encodeToString("cd".toByteArray()),
        )
        val assembler = ChunkAssembler()

        assertEquals(ChunkAssemblyResult.Pending, assembler.offer(first))
        assertTrue(assembler.offer(second) is ChunkAssemblyResult.Rejected)
    }

    @Test
    fun `rejects an encoded segment before allocating an oversized decoded array`() {
        val chunk = RelayFrame.MessageChunk(
            clientId = "c",
            streamId = "s",
            seqId = 1,
            direction = RelayDirection.SERVER_TO_CLIENT,
            segmentId = 0,
            segmentCount = 1,
            messageSizeBytes = 8,
            messageChunkBase64 = Base64.getEncoder().encodeToString("12345678".toByteArray()),
        )

        val result = ChunkAssembler(maxSegmentBytes = 4).offer(chunk)

        assertEquals(
            ChunkAssemblyResult.Rejected("encoded chunk exceeds segment limit"),
            result,
        )
    }

    @Test
    fun `bounds bytes buffered across incomplete messages`() {
        fun chunk(sequence: Long, text: String) = RelayFrame.MessageChunk(
            clientId = "c",
            streamId = "s",
            seqId = sequence,
            direction = RelayDirection.SERVER_TO_CLIENT,
            segmentId = 0,
            segmentCount = 2,
            messageSizeBytes = 4,
            messageChunkBase64 = Base64.getEncoder().encodeToString(text.toByteArray()),
        )
        val assembler = ChunkAssembler(maxBufferedBytes = 3)

        assertEquals(ChunkAssemblyResult.Pending, assembler.offer(chunk(1, "ab")))
        assertEquals(
            ChunkAssemblyResult.Rejected("global chunk buffer limit exceeded"),
            assembler.offer(chunk(2, "cd")),
        )
        assembler.invalidate("c", "s")
        assertEquals(ChunkAssemblyResult.Pending, assembler.offer(chunk(3, "ef")))
    }

    @Test
    fun `invalidates partial stream state`() {
        val chunk = RelayFrame.MessageChunk(
            clientId = "c",
            streamId = "s",
            seqId = 1,
            direction = RelayDirection.SERVER_TO_CLIENT,
            segmentId = 0,
            segmentCount = 2,
            messageSizeBytes = 4,
            messageChunkBase64 = "e30=",
        )
        val assembler = ChunkAssembler()
        assertEquals(ChunkAssemblyResult.Pending, assembler.offer(chunk))
        assembler.invalidate("c", "s")
        assertEquals(ChunkAssemblyResult.Pending, assembler.offer(chunk))
    }
}
