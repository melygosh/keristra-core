package com.grimels.keristra.core.llm

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ChatRequestParameters

internal fun LlmRequest.toChatRequest(parameters: ChatRequestParameters): ChatRequest =
    ChatRequest.builder()
        .messages(SystemMessage.from(system), UserMessage.from(user))
        .parameters(parameters)
        .build()

/** Calls the model and records wall-clock latency and the model id the provider reports it served. */
internal fun ChatModel.completeTimed(configuredModelId: String, chatRequest: ChatRequest): LlmResponse {
    val started = System.nanoTime()
    val response = chat(chatRequest)
    val latencyMs = (System.nanoTime() - started) / 1_000_000
    return LlmResponse(
        text = response.aiMessage().text(),
        modelId = response.metadata()?.modelName() ?: configuredModelId,
        inputTokens = response.tokenUsage()?.inputTokenCount(),
        outputTokens = response.tokenUsage()?.outputTokenCount(),
        latencyMs = latencyMs,
    )
}
