package app.planner.assistant.llm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Encodes requests for, and parses replies from, the Anthropic Messages API (sent through the Worker, which adds the
 * key, the version header and the spend cap). Non-streamed: the Worker forces `stream: false`.
 */
object MessagesJson {
    private val ephemeral = buildJsonObject { put("type", "ephemeral") }

    fun encodeRequest(request: LlmRequest): String = buildJsonObject {
        request.model?.let { put("model", it) }
        put("max_tokens", request.maxTokens)
        // The system prompt is frozen for the conversation, so it carries a cache breakpoint (it also covers the
        // tools, which render first). Top-level cache_control caches the growing conversation as well.
        put(
            "system",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", request.system)
                        put("cache_control", ephemeral)
                    },
                )
            },
        )
        put("cache_control", ephemeral)
        put("tools", request.tools)
        request.effort?.let { effort -> put("output_config", buildJsonObject { put("effort", effort) }) }
        put("messages", JsonArray(request.messages.map(::encodeMessage)))
    }.toString()

    fun encodeMessage(message: Message): JsonObject = buildJsonObject {
        put("role", message.role.name.lowercase())
        put("content", JsonArray(message.content.map(::encodeBlock)))
    }

    private fun encodeBlock(block: ContentBlock): JsonObject = when (block) {
        is ContentBlock.Text -> buildJsonObject {
            put("type", "text")
            put("text", block.text)
        }
        is ContentBlock.ToolUse -> buildJsonObject {
            put("type", "tool_use")
            put("id", block.id)
            put("name", block.name)
            put("input", block.input)
        }
        is ContentBlock.ToolResult -> buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", block.toolUseId)
            put("content", block.content)
            if (block.isError) put("is_error", true)
        }
        is ContentBlock.Opaque -> block.json
    }

    /** Reads a message written by [encodeMessage] (used for the saved conversation). */
    fun decodeMessage(json: JsonObject): Message {
        val role = when (json.string("role")) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> throw IllegalArgumentException("Unknown role")
        }
        val content = (json["content"] as? JsonArray ?: throw IllegalArgumentException("Message has no content"))
            .map { decodeBlock(it as? JsonObject ?: throw IllegalArgumentException("Block is not an object")) }
        return Message(role, content)
    }

    private fun decodeBlock(block: JsonObject): ContentBlock = when (block.string("type")) {
        "text" -> ContentBlock.Text(block.string("text") ?: "")
        "tool_use" -> ContentBlock.ToolUse(
            id = block.string("id") ?: throw IllegalArgumentException("tool_use without id"),
            name = block.string("name") ?: throw IllegalArgumentException("tool_use without name"),
            input = block["input"] as? JsonObject ?: JsonObject(emptyMap()),
        )
        "tool_result" -> ContentBlock.ToolResult(
            toolUseId = block.string("tool_use_id") ?: throw IllegalArgumentException("tool_result without id"),
            content = when (val c = block["content"]) {
                is JsonPrimitive -> c.contentOrNull ?: ""
                null -> ""
                else -> c.toString()
            },
            isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
        )
        else -> ContentBlock.Opaque(block)
    }

    /** Throws [IllegalArgumentException] if [body] is not a message (for example an error object). */
    fun parseResponse(body: String): LlmResponse {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw IllegalArgumentException("Response is not a JSON object")
        if (root.string("type") == "error") throw IllegalArgumentException("Response is an error")
        val content = root["content"] as? JsonArray ?: throw IllegalArgumentException("Response has no content")
        val blocks = afterLastFallback(content.map { decodeBlock(it as? JsonObject ?: throw IllegalArgumentException("Block is not an object")) })
        val usage = (root["usage"] as? JsonObject)?.let {
            TokenUsage(
                input = it.int("input_tokens"),
                output = it.int("output_tokens"),
                cacheWrite = it.int("cache_creation_input_tokens"),
                cacheRead = it.int("cache_read_input_tokens"),
            )
        } ?: TokenUsage()
        return LlmResponse(Message(Role.ASSISTANT, blocks), root.string("stop_reason"), usage, model = root.string("model"))
    }

    /**
     * Server-side fallback: when one model declines and another continues, a `fallback` block marks the switch.
     * Thinking and tool calls from before the last switch must not be echoed back (and the tool calls must not run);
     * text before it is kept. The marker itself is dropped.
     */
    private fun afterLastFallback(blocks: List<ContentBlock>): List<ContentBlock> {
        val last = blocks.indexOfLast { it is ContentBlock.Opaque && it.type == "fallback" }
        if (last < 0) return blocks
        val before = blocks.subList(0, last).filterIsInstance<ContentBlock.Text>()
        val after = blocks.subList(last + 1, blocks.size).filterNot { it is ContentBlock.Opaque && it.type == "fallback" }
        return before + after
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
