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
