package com.grimels.keristra.core.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AnthropicLlmClientTest {

    private val messagesResponse = """
        {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-test-20990101",
         "content": [{"type": "text", "text": "<definitions/>"}],
         "stop_reason": "end_turn", "usage": {"input_tokens": 120, "output_tokens": 45}}
    """

    @Test
    fun `sends system, user, model, temperature and max tokens to the Messages API`() {
        StubLlmServer(messagesResponse).use { server ->
            val client = AnthropicLlmClient(modelId = "claude-test", apiKey = "test-key", baseUrl = "${server.baseUrl}/v1/")

            client.complete(LlmRequest(system = "You write DMN.", user = "Rules: ...", temperature = 0.7, seed = 42, maxTokens = 1000))

            val request = server.lastRequest
            assertThat(request.path).isEqualTo("/v1/messages")
            assertThat(request.headers["x-api-key"]).isEqualTo("test-key")
            assertThat(request.body["model"].asString).isEqualTo("claude-test")
            assertThat(request.body["max_tokens"].asInt).isEqualTo(1000)
            assertThat(request.body["temperature"].asDouble).isEqualTo(0.7)
            assertThat(request.body.toString()).contains("You write DMN.")
            assertThat(request.body["messages"].asJsonArray.single().asJsonObject["role"].asString).isEqualTo("user")
            assertThat(request.body.toString()).contains("Rules: ...")
            assertThat(request.body.has("seed")).describedAs("Anthropic has no seed parameter").isFalse()
        }
    }

    @Test
    fun `omits temperature when the request does not set it`() {
        StubLlmServer(messagesResponse).use { server ->
            val client = AnthropicLlmClient(modelId = "claude-test", apiKey = "test-key", baseUrl = "${server.baseUrl}/v1/")

            client.complete(LlmRequest(system = "s", user = "u"))

            assertThat(server.lastRequest.body.has("temperature")).isFalse()
        }
    }

    @Test
    fun `maps text, served model id, token usage and latency`() {
        StubLlmServer(messagesResponse).use { server ->
            val client = AnthropicLlmClient(modelId = "claude-test", apiKey = "test-key", baseUrl = "${server.baseUrl}/v1/")

            val response = client.complete(LlmRequest(system = "s", user = "u"))

            assertThat(client.modelId).isEqualTo("claude-test")
            assertThat(response.text).isEqualTo("<definitions/>")
            assertThat(response.modelId).isEqualTo("claude-test-20990101")
            assertThat(response.inputTokens).isEqualTo(120)
            assertThat(response.outputTokens).isEqualTo(45)
            assertThat(response.latencyMs).isGreaterThanOrEqualTo(0)
        }
    }
}
