package app.planner.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Encodes requests for, and parses replies from, the Anthropic Messages API (through our proxy). */
object AnthropicJson {
    fun encodeRequest(request: LlmRequest): String = buildJsonObject {
        put("max_tokens", request.maxTokens)
        // The model is deliberately absent: the proxy chooses it from the request's purpose.
        put(
            "system",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", request.system)
                        put("cache_control", buildJsonObject { put("type", "ephemeral") })
                    },
                )
            },
        )
        put("tools", request.tools)
        put("messages", JsonArray(request.messages.map(::encodeMessage)))
    }.toString()

    private fun encodeMessage(message: Message): JsonObject = buildJsonObject {
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
    }

    /** Throws [IllegalArgumentException] if [body] is not a message (for example an error object). */
    fun parseResponse(body: String): LlmResponse {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw IllegalArgumentException("Response is not a JSON object")
        val content = root["content"] as? JsonArray ?: throw IllegalArgumentException("Response has no content")

        val blocks = content.mapNotNull { element ->
            val block = element.jsonObject
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> ContentBlock.Text(block.string("text") ?: "")
                "tool_use" -> ContentBlock.ToolUse(
                    id = block.string("id") ?: throw IllegalArgumentException("tool_use without id"),
                    name = block.string("name") ?: throw IllegalArgumentException("tool_use without name"),
                    input = block["input"] as? JsonObject ?: JsonObject(emptyMap()),
                )
                else -> null
            }
        }
        val usage = (root["usage"] as? JsonObject)?.let {
            TokenUsage(
                input = it.int("input_tokens"),
                output = it.int("output_tokens"),
                cacheWrite = it.int("cache_creation_input_tokens"),
                cacheRead = it.int("cache_read_input_tokens"),
            )
        } ?: TokenUsage()
        return LlmResponse(Message(Role.ASSISTANT, blocks), root.string("stop_reason"), usage)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
