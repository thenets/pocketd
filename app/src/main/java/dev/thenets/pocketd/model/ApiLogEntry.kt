package dev.thenets.pocketd.model

data class ApiLogEntry(
    val id: Long,
    val timestamp: Long,
    val method: String,
    val path: String,
    val statusCode: Int,
    val durationMs: Long,
    val tokensGenerated: Int? = null,
    val isStreaming: Boolean = false,
    val requestBody: String? = null,
    val responseBody: String? = null,
)
