package dev.thenets.pocketd.llm

import android.util.Log
import dev.thenets.pocketd.model.FunctionCall
import dev.thenets.pocketd.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private const val TAG = "ToolCallParser"

/**
 * Parses LLM output text and extracts a tool call if one is present.
 *
 * Returns null if:
 *  - No tool call pattern is found (plain text response)
 *  - JSON parsing fails (caller should treat output as plain text)
 */
object ToolCallParser {

    // Primary: <tool_call>…</tool_call>
    private val TAG_REGEX = Regex(
        """<tool_call>\s*([\s\S]*?)\s*</tool_call>""",
        RegexOption.IGNORE_CASE
    )

    // Fallback: bare JSON object with "name" + "arguments" (model ignored tag instructions)
    private val RAW_JSON_REGEX = Regex(
        """\{\s*"name"\s*:\s*"[^"]+"\s*,[\s\S]*?"arguments"\s*:[\s\S]*?\}(?=\s*$)""",
        RegexOption.IGNORE_CASE
    )

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(output: String): ToolCall? {
        val trimmed = output.trim()

        val json = TAG_REGEX.find(trimmed)?.groupValues?.get(1)
            ?: RAW_JSON_REGEX.find(trimmed)?.value
            ?: return null

        return parseJson(json)
    }

    private fun parseJson(raw: String): ToolCall? = try {
        val obj = lenientJson.parseToJsonElement(raw).jsonObject

        val name = obj["name"]?.jsonPrimitive?.content
            ?: return null.also { Log.w(TAG, "missing 'name': $raw") }

        val argumentsElement = obj["arguments"]
            ?: return null.also { Log.w(TAG, "missing 'arguments': $raw") }

        // Model naturally outputs arguments as an object; OpenAI spec requires a JSON string.
        val arguments = when (argumentsElement) {
            is JsonObject -> argumentsElement.toString()
            else          -> argumentsElement.jsonPrimitive.content
        }

        ToolCall(
            id       = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}",
            type     = "function",
            function = FunctionCall(name = name, arguments = arguments)
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse tool call: $raw — ${e.message}")
        null
    }
}
