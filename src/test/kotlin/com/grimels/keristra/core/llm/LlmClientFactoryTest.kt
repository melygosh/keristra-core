package com.grimels.keristra.core.llm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LlmClientFactoryTest {

    private val factory = LlmClientFactory(
        LlmProperties(
            providers = mapOf(
                "anthropic" to LlmProperties.Provider(type = ProviderType.ANTHROPIC, apiKey = "test-key"),
                "local" to LlmProperties.Provider(type = ProviderType.OPENAI_COMPATIBLE, baseUrl = "http://localhost:11434/v1"),
            ),
        ),
    )

    @Test
    fun `creates an Anthropic client from a provider-colon-model spec`() {
        val client = factory.create("anthropic:claude-test")

        assertThat(client).isInstanceOf(AnthropicLlmClient::class.java)
        assertThat(client.modelId).isEqualTo("claude-test")
    }

    @Test
    fun `splits on the first colon only, so Ollama tags survive`() {
        val client = factory.create("local:gemma4:26b")

        assertThat(client).isInstanceOf(OpenAiCompatibleLlmClient::class.java)
        assertThat(client.modelId).isEqualTo("gemma4:26b")
    }

    @Test
    fun `every call returns a new instance, so tracks never share a client`() {
        assertThat(factory.create("local:gemma4:26b")).isNotSameAs(factory.create("local:gemma4:26b"))
    }

    @Test
    fun `unknown provider names the configured ones`() {
        assertThatThrownBy { factory.create("openrouter:some-model") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("openrouter")
            .hasMessageContaining("anthropic")
            .hasMessageContaining("local")
    }

    @Test
    fun `spec without a model is rejected`() {
        assertThatThrownBy { factory.create("anthropic") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { factory.create("anthropic:") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
