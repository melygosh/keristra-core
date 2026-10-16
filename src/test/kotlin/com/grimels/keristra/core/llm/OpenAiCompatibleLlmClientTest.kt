package com.grimels.keristra.core.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenAiCompatibleLlmClientTest {

    private val completionResponse = """
        {"id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "gemma4:26b",
         "choices": [{"index": 0, "message": {"role": "assistant", "content": "[{\"object\": {}}]"}, "finish_reason": "stop"}],
         "usage": {"prompt_tokens": 70, "completion_tokens": 30, "total_tokens": 100}}
    """

    @Test
    fun `sends system and user messages, model, temperature, seed and max tokens to chat completions`() {
        StubLlmServer(completionResponse).use { server ->
            val client = OpenAiCompatibleLlmClient(modelId = "gemma4:26b", baseUrl = "${server.baseUrl}/v1")

            client.complete(LlmRequest(system = "You write tests.", user = "Rules: ...", temperature = 0.2, seed = 42, maxTokens = 2000))

            val request = server.lastRequest
            assertThat(request.path).isEqualTo("/v1/chat/completions")
            assertThat(request.body["model"].asString).isEqualTo("gemma4:26b")
            assertThat(request.body["temperature"].asDouble).isEqualTo(0.2)
            assertThat(request.body["seed"].asLong).isEqualTo(42)
            assertThat(request.body.toString()).containsAnyOf("\"max_tokens\":2000", "\"max_completion_tokens\":2000")
            val messages = request.body["messages"].asJsonArray.map { it.asJsonObject }
            assertThat(messages.map { it["role"].asString }).containsExactly("system", "user")
            assertThat(messages.map { it["content"].asString }).containsExactly("You write tests.", "Rules: ...")
        }
    }

    @Test
    fun `works against local servers without an API key`() {
        StubLlmServer(completionResponse).use { server ->
            val client = OpenAiCompatibleLlmClient(modelId = "gemma4:26b", baseUrl = "${server.baseUrl}/v1", apiKey = null)

            val response = client.complete(LlmRequest(system = "s", user = "u"))

            assertThat(response.text).isEqualTo("[{\"object\": {}}]")
        }
    }

    @Test
    fun `maps text, served model id, token usage and latency`() {
        StubLlmServer(completionResponse).use { server ->
            val client = OpenAiCompatibleLlmClient(modelId = "gemma4:26b", baseUrl = "${server.baseUrl}/v1")

            val response = client.complete(LlmRequest(system = "s", user = "u"))

            assertThat(response.modelId).isEqualTo("gemma4:26b")
            assertThat(response.inputTokens).isEqualTo(70)
            assertThat(response.outputTokens).isEqualTo(30)
            assertThat(response.latencyMs).isGreaterThanOrEqualTo(0)
        }
    }
}
