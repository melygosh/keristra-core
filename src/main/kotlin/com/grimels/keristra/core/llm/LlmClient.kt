package com.grimels.keristra.core.llm

/** One provider + model. Each generation track owns its own instance (see [LlmClientFactory]). */
interface LlmClient {
    /** Model id as configured; the id the provider actually served is in [LlmResponse.modelId]. */
    val modelId: String
    fun complete(request: LlmRequest): LlmResponse
}

/** [seed] is honoured only by providers that support it (OpenAI-compatible servers); Anthropic has none. */
data class LlmRequest(
    val system: String,
    val user: String,
    val temperature: Double? = null,
    val seed: Long? = null,
    val maxTokens: Int = 8192,
)

data class LlmResponse(
    val text: String,
    val modelId: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long,
)
