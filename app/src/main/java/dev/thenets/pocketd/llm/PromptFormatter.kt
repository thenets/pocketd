package dev.thenets.pocketd.llm

import dev.thenets.pocketd.model.ChatMessage

/**
 * Converts an OpenAI-style messages list into a single prompt string
 * compatible with Gemma instruction-tuned models (the default model family).
 *
 * Gemma IT format:
 *   <start_of_turn>user\n{content}<end_of_turn>\n
 *   <start_of_turn>model\n{content}<end_of_turn>\n
 *
 * For other model families, swap this implementation while keeping
 * LlmEngine and HttpServer unchanged.
 */
object PromptFormatter {

    private const val BOS = "<bos>"
    private const val SOT = "<start_of_turn>"
    private const val EOT = "<end_of_turn>"

    fun format(messages: List<ChatMessage>): String {
        val sb = StringBuilder(BOS).append('\n')
        for (msg in messages) {
            val role = when (msg.role) {
                "assistant" -> "model"
                "system"    -> "user"   // Gemma has no system role; prepend to user turn
                else        -> "user"
            }
            sb.append(SOT).append(role).append('\n')
            if (msg.role == "system") {
                // Make system context explicit inside the user turn
                sb.append("[System: ").append(msg.content).append("]\n")
            } else {
                sb.append(msg.content)
            }
            sb.append(EOT).append('\n')
        }
        // Open the model response turn
        sb.append(SOT).append("model").append('\n')
        return sb.toString()
    }
}
