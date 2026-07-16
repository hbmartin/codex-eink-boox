package me.haroldmartin.codexeink.protocol

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class RelayDirection {
    CLIENT_TO_SERVER,
    SERVER_TO_CLIENT,
}

enum class RelayPongStatus {
    ACTIVE,
    UNKNOWN,
}

/**
 * Public relay envelope primitives mirrored from Codex's open-source remote-control host transport.
 * Enrollment and controller authentication are intentionally not represented here because there is
 * no public third-party controller enrollment contract.
 */
sealed interface RelayFrame {
    val clientId: String
    val streamId: String?
    val seqId: Long?
    val cursor: String?
    val direction: RelayDirection

    data class Message(
        override val clientId: String,
        override val streamId: String?,
        override val seqId: Long?,
        override val cursor: String? = null,
        override val direction: RelayDirection,
        val message: JsonRpcMessage,
    ) : RelayFrame

    data class MessageChunk(
        override val clientId: String,
        override val streamId: String?,
        override val seqId: Long?,
        override val cursor: String? = null,
        override val direction: RelayDirection,
        val segmentId: Int,
        val segmentCount: Int,
        val messageSizeBytes: Int,
        val messageChunkBase64: String,
    ) : RelayFrame

    data class Ack(
        override val clientId: String,
        override val streamId: String?,
        override val seqId: Long?,
        override val cursor: String? = null,
        override val direction: RelayDirection,
        val segmentId: Int? = null,
    ) : RelayFrame

    data class Ping(
        override val clientId: String,
        override val streamId: String? = null,
        override val seqId: Long? = null,
        override val cursor: String? = null,
        override val direction: RelayDirection = RelayDirection.CLIENT_TO_SERVER,
    ) : RelayFrame

    data class Pong(
        override val clientId: String,
        override val streamId: String,
        override val seqId: Long,
        override val cursor: String? = null,
        override val direction: RelayDirection = RelayDirection.SERVER_TO_CLIENT,
        val status: RelayPongStatus,
    ) : RelayFrame

    data class ClientClosed(
        override val clientId: String,
        override val streamId: String?,
        override val seqId: Long? = null,
        override val cursor: String? = null,
        override val direction: RelayDirection = RelayDirection.CLIENT_TO_SERVER,
    ) : RelayFrame

    data class Unknown(
        override val clientId: String,
        override val streamId: String?,
        override val seqId: Long?,
        override val cursor: String?,
        override val direction: RelayDirection,
        val type: String,
        val raw: JsonObject,
    ) : RelayFrame
}

class RelayEnvelopeCodecException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Codec for the documented open-source host envelope shape. Unknown event types are returned as
 * [RelayFrame.Unknown], preserving the complete object for compatibility diagnostics.
 */
class RelayEnvelopeCodec(
    private val expectedInboundDirection: RelayDirection = RelayDirection.SERVER_TO_CLIENT,
    private val jsonRpcCodec: JsonRpcCodec = JsonRpcCodec(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decode(text: String): RelayFrame {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: throw RelayEnvelopeCodecException("Relay frame must be an object")
        } catch (error: RelayEnvelopeCodecException) {
            throw error
        } catch (error: Exception) {
            throw RelayEnvelopeCodecException("Malformed relay frame", error)
        }

        val type = root.string("type") ?: throw RelayEnvelopeCodecException("Relay frame is missing type")
        val clientId = root.string("client_id")
            ?: throw RelayEnvelopeCodecException("Relay frame is missing client_id")
        val streamId = root.string("stream_id")
        val seqId = root["seq_id"]?.jsonPrimitive?.longOrNull
        val cursor = root.string("cursor")

        return when (type) {
            "client_message", "server_message" -> {
                val direction = if (type == "client_message") {
                    RelayDirection.CLIENT_TO_SERVER
                } else {
                    RelayDirection.SERVER_TO_CLIENT
                }
                val messageObject = root["message"] as? JsonObject
                    ?: throw RelayEnvelopeCodecException("Relay message payload must be an object")
                RelayFrame.Message(
                    clientId = clientId,
                    streamId = streamId,
                    seqId = seqId,
                    cursor = cursor,
                    direction = direction,
                    message = jsonRpcCodec.decode(messageObject.toString()),
                )
            }
            "client_message_chunk", "server_message_chunk" -> {
                val direction = if (type == "client_message_chunk") {
                    RelayDirection.CLIENT_TO_SERVER
                } else {
                    RelayDirection.SERVER_TO_CLIENT
                }
                RelayFrame.MessageChunk(
                    clientId = clientId,
                    streamId = streamId,
                    seqId = seqId,
                    cursor = cursor,
                    direction = direction,
                    segmentId = root.requiredInt("segment_id"),
                    segmentCount = root.requiredInt("segment_count"),
                    messageSizeBytes = root.requiredInt("message_size_bytes"),
                    messageChunkBase64 = root.string("message_chunk_base64")
                        ?: throw RelayEnvelopeCodecException("Relay chunk is missing message_chunk_base64"),
                )
            }
            "ack" -> RelayFrame.Ack(
                clientId = clientId,
                streamId = streamId,
                seqId = seqId,
                cursor = cursor,
                direction = expectedInboundDirection,
                segmentId = root["segment_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull,
            )
            "ping" -> RelayFrame.Ping(
                clientId = clientId,
                streamId = streamId,
                seqId = seqId,
                cursor = cursor,
            )
            "pong" -> RelayFrame.Pong(
                clientId = clientId,
                streamId = streamId ?: throw RelayEnvelopeCodecException("Pong is missing stream_id"),
                seqId = seqId ?: throw RelayEnvelopeCodecException("Pong is missing seq_id"),
                cursor = cursor,
                status = when (root.string("status")) {
                    "active" -> RelayPongStatus.ACTIVE
                    else -> RelayPongStatus.UNKNOWN
                },
            )
            "client_closed" -> RelayFrame.ClientClosed(
                clientId = clientId,
                streamId = streamId,
                seqId = seqId,
                cursor = cursor,
            )
            else -> RelayFrame.Unknown(
                clientId = clientId,
                streamId = streamId,
                seqId = seqId,
                cursor = cursor,
                direction = expectedInboundDirection,
                type = type,
                raw = root,
            )
        }
    }

    fun encode(frame: RelayFrame): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("type", frame.wireType())
            put("client_id", frame.clientId)
            frame.streamId?.let { put("stream_id", it) }
            frame.seqId?.let { put("seq_id", it) }
            frame.cursor?.let { put("cursor", it) }
            when (frame) {
                is RelayFrame.Message -> {
                    val message = json.parseToJsonElement(jsonRpcCodec.encode(frame.message))
                    put("message", message)
                }
                is RelayFrame.MessageChunk -> {
                    put("segment_id", frame.segmentId)
                    put("segment_count", frame.segmentCount)
                    put("message_size_bytes", frame.messageSizeBytes)
                    put("message_chunk_base64", frame.messageChunkBase64)
                }
                is RelayFrame.Ack -> frame.segmentId?.let { put("segment_id", it) }
                is RelayFrame.Pong -> put(
                    "status",
                    if (frame.status == RelayPongStatus.ACTIVE) "active" else "unknown",
                )
                is RelayFrame.Unknown -> frame.raw.forEach { (key, value) ->
                    if (key !in ENVELOPE_KEYS) put(key, value)
                }
                is RelayFrame.Ping,
                is RelayFrame.ClientClosed,
                -> Unit
            }
        },
    )

    private fun RelayFrame.wireType(): String = when (this) {
        is RelayFrame.Message -> if (direction == RelayDirection.CLIENT_TO_SERVER) {
            "client_message"
        } else {
            "server_message"
        }
        is RelayFrame.MessageChunk -> if (direction == RelayDirection.CLIENT_TO_SERVER) {
            "client_message_chunk"
        } else {
            "server_message_chunk"
        }
        is RelayFrame.Ack -> "ack"
        is RelayFrame.Ping -> "ping"
        is RelayFrame.Pong -> "pong"
        is RelayFrame.ClientClosed -> "client_closed"
        is RelayFrame.Unknown -> type
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredInt(key: String): Int = this[key]?.jsonPrimitive?.intOrNull
        ?: throw RelayEnvelopeCodecException("Relay frame is missing integer $key")

    private companion object {
        val ENVELOPE_KEYS = setOf("type", "client_id", "stream_id", "seq_id", "cursor")
    }
}

sealed interface ChunkAssemblyResult {
    data object Pending : ChunkAssemblyResult
    data object Duplicate : ChunkAssemblyResult
    data class Complete(val messageJson: String) : ChunkAssemblyResult
    data class Rejected(val reason: String) : ChunkAssemblyResult
}

/** Bounded, replay-safe chunk assembler. Chunks may arrive out of order and exact duplicates are ignored. */
class ChunkAssembler(
    private val maxSegmentCount: Int = 1_024,
    private val maxMessageBytes: Int = 100 * 1024 * 1024,
    private val maxConcurrentAssemblies: Int = 128,
) {
    private data class Key(val clientId: String, val streamId: String, val seqId: Long)
    private data class Assembly(
        val segmentCount: Int,
        val messageSizeBytes: Int,
        val segments: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private val assemblies = ConcurrentHashMap<Key, Assembly>()

    @Synchronized
    fun offer(chunk: RelayFrame.MessageChunk): ChunkAssemblyResult {
        val streamId = chunk.streamId ?: return ChunkAssemblyResult.Rejected("missing stream_id")
        val seqId = chunk.seqId ?: return ChunkAssemblyResult.Rejected("missing seq_id")
        if (chunk.segmentCount !in 1..maxSegmentCount) {
            return ChunkAssemblyResult.Rejected("invalid segment_count")
        }
        if (chunk.segmentId !in 0 until chunk.segmentCount) {
            return ChunkAssemblyResult.Rejected("invalid segment_id")
        }
        if (chunk.messageSizeBytes !in 1..maxMessageBytes) {
            return ChunkAssemblyResult.Rejected("invalid message_size_bytes")
        }
        val decoded = try {
            Base64.getDecoder().decode(chunk.messageChunkBase64)
        } catch (_: IllegalArgumentException) {
            return ChunkAssemblyResult.Rejected("invalid base64")
        }
        if (decoded.isEmpty() || decoded.size > chunk.messageSizeBytes) {
            return ChunkAssemblyResult.Rejected("invalid decoded chunk size")
        }

        val key = Key(chunk.clientId, streamId, seqId)
        var assembly = assemblies[key]
        if (assembly == null) {
            if (assemblies.size >= maxConcurrentAssemblies) {
                return ChunkAssemblyResult.Rejected("too many concurrent assemblies")
            }
            assembly = Assembly(chunk.segmentCount, chunk.messageSizeBytes)
            assemblies[key] = assembly
        }
        if (assembly.segmentCount != chunk.segmentCount || assembly.messageSizeBytes != chunk.messageSizeBytes) {
            assemblies.remove(key)
            return ChunkAssemblyResult.Rejected("chunk metadata changed")
        }

        val existing = assembly.segments[chunk.segmentId]
        if (existing != null) {
            return if (existing.contentEquals(decoded)) {
                ChunkAssemblyResult.Duplicate
            } else {
                assemblies.remove(key)
                ChunkAssemblyResult.Rejected("duplicate segment content changed")
            }
        }
        assembly.segments[chunk.segmentId] = decoded
        if (assembly.segments.size < assembly.segmentCount) return ChunkAssemblyResult.Pending

        val totalSize = assembly.segments.values.sumOf(ByteArray::size)
        if (totalSize != assembly.messageSizeBytes) {
            assemblies.remove(key)
            return ChunkAssemblyResult.Rejected("reassembled size mismatch")
        }
        val bytes = ByteArray(totalSize)
        var offset = 0
        repeat(assembly.segmentCount) { index ->
            val segment = assembly.segments[index]
                ?: return ChunkAssemblyResult.Rejected("missing segment")
            segment.copyInto(bytes, destinationOffset = offset)
            offset += segment.size
        }
        assemblies.remove(key)
        return ChunkAssemblyResult.Complete(String(bytes, StandardCharsets.UTF_8))
    }

    fun invalidate(clientId: String, streamId: String? = null) {
        assemblies.keys.removeIf { key ->
            key.clientId == clientId && (streamId == null || key.streamId == streamId)
        }
    }

    companion object {
        const val DEFAULT_TARGET_SEGMENT_BYTES: Int = 100 * 1024

        fun segment(
            frame: RelayFrame.Message,
            jsonRpcCodec: JsonRpcCodec = JsonRpcCodec(),
            targetSegmentBytes: Int = DEFAULT_TARGET_SEGMENT_BYTES,
        ): List<RelayFrame.MessageChunk> {
            require(targetSegmentBytes > 0) { "targetSegmentBytes must be positive" }
            val streamId = requireNotNull(frame.streamId) { "Chunked relay messages require stream_id" }
            val seqId = requireNotNull(frame.seqId) { "Chunked relay messages require seq_id" }
            val bytes = jsonRpcCodec.encode(frame.message).toByteArray(StandardCharsets.UTF_8)
            val segmentCount = ceil(bytes.size.toDouble() / targetSegmentBytes).toInt().coerceAtLeast(1)
            return List(segmentCount) { segmentId ->
                val start = segmentId * targetSegmentBytes
                val end = minOf(start + targetSegmentBytes, bytes.size)
                RelayFrame.MessageChunk(
                    clientId = frame.clientId,
                    streamId = streamId,
                    seqId = seqId,
                    cursor = frame.cursor,
                    direction = frame.direction,
                    segmentId = segmentId,
                    segmentCount = segmentCount,
                    messageSizeBytes = bytes.size,
                    messageChunkBase64 = Base64.getEncoder().encodeToString(bytes.copyOfRange(start, end)),
                )
            }
        }
    }
}

/**
 * Bounded in-memory replay buffer for controller messages that have not yet received a backend ACK.
 * It is internal because persistence and enrollment-specific cursor recovery belong above the wire layer.
 */
internal class RelayReplayBuffer(
    private val codec: RelayEnvelopeCodec,
    private val maxPendingSequences: Int = 256,
    private val maxPendingBytes: Int = 5 * 1024 * 1024,
) {
    private data class Entry(val frame: RelayFrame, val encodedBytes: Int)

    private val entries = TreeMap<Long, List<Entry>>()
    private var totalBytes = 0

    @Synchronized
    fun record(frames: List<RelayFrame>): Boolean {
        if (frames.isEmpty()) return true
        val seqId = frames.first().seqId ?: return false
        if (frames.any { it.seqId != seqId || it.direction != RelayDirection.CLIENT_TO_SERVER }) return false
        if (entries.containsKey(seqId)) return true
        val encoded = frames.map { frame -> Entry(frame, codec.encode(frame).toByteArray(Charsets.UTF_8).size) }
        val addedBytes = encoded.sumOf(Entry::encodedBytes)
        if (entries.size >= maxPendingSequences || totalBytes + addedBytes > maxPendingBytes) return false
        entries[seqId] = encoded
        totalBytes += addedBytes
        return true
    }

    /** ACKs are cumulative by sequence; segmented ACKs are cumulative within the matching sequence. */
    @Synchronized
    fun acknowledge(ack: RelayFrame.Ack) {
        val seqId = ack.seqId ?: return
        entries.keys.filter { it < seqId }.forEach(::removeSequence)
        val current = entries[seqId] ?: return
        val segmentId = ack.segmentId
        if (segmentId == null) {
            removeSequence(seqId)
            return
        }
        val remaining = current.filter { entry ->
            val chunk = entry.frame as? RelayFrame.MessageChunk
            chunk == null || chunk.segmentId > segmentId
        }
        totalBytes -= current.sumOf(Entry::encodedBytes)
        if (remaining.isEmpty()) {
            entries.remove(seqId)
        } else {
            entries[seqId] = remaining
            totalBytes += remaining.sumOf(Entry::encodedBytes)
        }
    }

    @Synchronized
    fun replayFrames(cursor: String?): List<RelayFrame> = entries.values.flatten().map { entry ->
        entry.frame.withCursor(cursor)
    }

    @Synchronized
    fun pendingSequenceCount(): Int = entries.size

    private fun removeSequence(seqId: Long) {
        entries.remove(seqId)?.let { removed -> totalBytes -= removed.sumOf(Entry::encodedBytes) }
    }

    private fun RelayFrame.withCursor(cursor: String?): RelayFrame = when (this) {
        is RelayFrame.Message -> copy(cursor = cursor)
        is RelayFrame.MessageChunk -> copy(cursor = cursor)
        is RelayFrame.Ack -> copy(cursor = cursor)
        is RelayFrame.Ping -> copy(cursor = cursor)
        is RelayFrame.Pong -> copy(cursor = cursor)
        is RelayFrame.ClientClosed -> copy(cursor = cursor)
        is RelayFrame.Unknown -> copy(cursor = cursor)
    }
}
