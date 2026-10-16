package com.grimels.keristra.core.llm

import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequestParameters
import java.time.Duration

/** Anthropic Messages API via LangChain4J. [LlmRequest.seed] is ignored: the API has no seed. */
class AnthropicLlmClient(
    override val modelId: String,
    apiKey: String,
    baseUrl: String? = null,
    timeout: Duration = Duration.ofMinutes(5),
) : LlmClient {

    private val model: ChatModel = AnthropicChatModel.builder()
        .apiKey(apiKey)
        .modelName(modelId)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(timeout)
        .build()

    override fun complete(request: LlmRequest): LlmResponse {
        val parameters = ChatRequestParameters.builder()
            .temperature(request.temperature)
            .maxOutputTokens(request.maxTokens)
            .build()
        return model.completeTimed(modelId, request.toChatRequest(parameters))
    }
}
