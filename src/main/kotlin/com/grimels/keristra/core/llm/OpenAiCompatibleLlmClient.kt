package com.grimels.keristra.core.llm

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiChatRequestParameters
import java.time.Duration

/**
 * Any server exposing `/chat/completions` in the OpenAI format: Ollama (`http://host:11434/v1`),
 * LM Studio (`http://host:1234/v1`), llama.cpp server. Local servers need no API key.
 */
class OpenAiCompatibleLlmClient(
    override val modelId: String,
    baseUrl: String,
    apiKey: String? = null,
    timeout: Duration = Duration.ofMinutes(10),
) : LlmClient {

    private val model: ChatModel = OpenAiChatModel.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey ?: NO_API_KEY)
        .modelName(modelId)
        .timeout(timeout)
        .build()

    override fun complete(request: LlmRequest): LlmResponse {
        val parameters = OpenAiChatRequestParameters.builder()
            .temperature(request.temperature)
            .maxOutputTokens(request.maxTokens)
            .seed(request.seed?.let(Math::toIntExact))
            .build()
        return model.completeTimed(modelId, request.toChatRequest(parameters))
    }

    private companion object {
        /** Local servers ignore the key, but the client requires one. */
        const val NO_API_KEY = "not-needed"
    }
}
