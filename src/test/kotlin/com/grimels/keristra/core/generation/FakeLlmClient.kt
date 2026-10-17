package com.grimels.keristra.core.generation

import com.grimels.keristra.core.llm.LlmClient
import com.grimels.keristra.core.llm.LlmRequest
import com.grimels.keristra.core.llm.LlmResponse

/** Records requests and answers every call with [reply]. */
internal class FakeLlmClient(private val reply: String, override val modelId: String = "fake-model") : LlmClient {

    val requests = mutableListOf<LlmRequest>()

    override fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return LlmResponse(text = reply, modelId = modelId, inputTokens = 10, outputTokens = 20, latencyMs = 5)
    }
}

internal val fixedSchema = DomainContextProvider { domain -> """{"$domain": {"creditScore": "number"}}""" }

/** Answers with the scripted replies in order; a Throwable in the script is thrown instead. */
internal class ScriptedLlmClient(
    vararg replies: Any,
    override val modelId: String = "scripted",
    private val inputTokens: Int = 1,
    private val outputTokens: Int = 1,
) : LlmClient {
    private val script = ArrayDeque(replies.toList())
    val requests = mutableListOf<LlmRequest>()

    override fun complete(request: LlmRequest): LlmResponse {
        requests += request
        val next = script.removeFirstOrNull() ?: throw AssertionError("Unexpected LLM call #${requests.size}")
        if (next is Throwable) throw next
        return LlmResponse(next as String, modelId, inputTokens, outputTokens, latencyMs = 1)
    }
}
