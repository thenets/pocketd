package dev.thenets.pocketd.llm

import dev.thenets.pocketd.model.ChatMessage
import dev.thenets.pocketd.model.ToolDefinition

/**
 * Converts an OpenAI-style messages list into a single prompt string
 * compatible with Gemma instruction-tuned models (the default model family).
 *
 * Gemma IT format:
 *   <start_of_turn>user\n{content}<end_of_turn>\n
 *   <start_of_turn>model\n{content}<end_of_turn>\n
 *
 * When tools are supplied, a compact system-style prefix is injected as the
 * first user turn instructing the model to output <tool_call>…</tool_call>
 * blocks. Tool results (role="tool") are rendered as user turns.
 */
object PromptFormatter {

    private const val BOS = "<bos>"
    private const val SOT = "<start_of_turn>"
    private const val EOT = "<end_of_turn>"

    fun format(messages: List<ChatMessage>, tools: List<ToolDefinition> = emptyList()): String =
        buildPrompt(messages, tools, includeSystem = true)

    /**
     * Same as [format] but skips system messages — use when the system instruction
     * is passed via [ConversationConfig.systemInstruction] instead.
     */
    fun formatWithoutSystem(messages: List<ChatMessage>, tools: List<ToolDefinition> = emptyList()): String =
        buildPrompt(messages, tools, includeSystem = false)

    /**
     * Extracts the concatenated text of all system-role messages.
     * Returns null if there are no system messages.
     */
    fun extractSystemInstruction(messages: List<ChatMessage>): String? {
        val parts = messages.filter { it.role == "system" }.mapNotNull { it.textContent() }
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }

    private fun buildPrompt(messages: List<ChatMessage>, tools: List<ToolDefinition>, includeSystem: Boolean): String {
        val sb = StringBuilder(BOS).append('\n')

        // Inject tool definitions as first user turn
        if (tools.isNotEmpty()) {
            sb.append(SOT).append("user").append('\n')
            sb.append(buildToolSystemPrompt(tools))
            sb.append(EOT).append('\n')
        }

        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    if (includeSystem) {
                        sb.append(SOT).append("user").append('\n')
                        sb.append("[System: ").append(msg.textContent()).append("]\n")
                        sb.append(EOT).append('\n')
                    }
                    // When !includeSystem, skip — system instruction goes via ConversationConfig
                }
                "user" -> {
                    sb.append(SOT).append("user").append('\n')
                    sb.append(msg.textContent() ?: "")
                    sb.append(EOT).append('\n')
                }
                "assistant" -> {
                    sb.append(SOT).append("model").append('\n')
                    if (!msg.toolCalls.isNullOrEmpty()) {
                        for (tc in msg.toolCalls) {
                            sb.append("<tool_call>\n")
                            sb.append("""{"name": "${tc.function.name}", "arguments": ${tc.function.arguments}}""")
                            sb.append("\n</tool_call>\n")
                        }
                    } else {
                        sb.append(msg.textContent() ?: "")
                    }
                    sb.append(EOT).append('\n')
                }
                "tool" -> {
                    sb.append(SOT).append("user").append('\n')
                    sb.append("<tool_result>\n")
                    val contentJson = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.json.JsonPrimitive(msg.textContent() ?: "")
                    )
                    sb.append("""{"tool_call_id": "${msg.toolCallId}", "content": $contentJson}""")
                    sb.append("\n</tool_result>\n")
                    sb.append(EOT).append('\n')
                }
                else -> {
                    sb.append(SOT).append("user").append('\n')
                    sb.append(msg.textContent() ?: "")
                    sb.append(EOT).append('\n')
                }
            }
        }

        sb.append(SOT).append("model").append('\n')
        return sb.toString()
    }

    private fun buildToolSystemPrompt(tools: List<ToolDefinition>): String {
        val sb = StringBuilder()
        sb.append("[System: You have access to the following tools. ")
        sb.append("When you need to call a tool, respond ONLY with a tool call block — no other text:\n")
        sb.append("<tool_call>\n")
        sb.append("{\"name\": \"<tool_name>\", \"arguments\": {<json_arguments>}}\n")
        sb.append("</tool_call>\n")
        sb.append("Available tools:\n")
        for (tool in tools) {
            sb.append("- ").append(tool.function.name)
            if (tool.function.description != null) {
                sb.append(": ").append(tool.function.description)
            }
            if (tool.function.parameters != null) {
                sb.append(" | params: ").append(tool.function.parameters.toString())
            }
            sb.append('\n')
        }
        sb.append("]\n")
        return sb.toString()
    }
}
