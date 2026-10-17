package com.grimels.keristra.core.llm

import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

enum class ProviderType { ANTHROPIC, OPENAI_COMPATIBLE }

/** `keristra.llm.providers.<name>.*` — connection settings per provider; the model is chosen per run. */
@ConfigurationProperties("keristra.llm")
data class LlmProperties(val providers: Map<String, Provider> = emptyMap()) {
    data class Provider(
        val type: ProviderType,
        val baseUrl: String? = null,
        val apiKey: String? = null,
        val timeout: Duration? = null,
    )
}

@Configuration
@EnableConfigurationProperties(LlmProperties::class)
class LlmConfig

/**
 * Builds clients from `<provider>:<model>` specs, e.g. `anthropic:claude-sonnet-4-20250514` or `local:gpt-oss:20b`
 * (split on the first colon, so Ollama tags keep theirs). Every call returns a new instance, so the DMN track
 * and the test track never share a client.
 */
@Component
class LlmClientFactory(private val properties: LlmProperties) {

    private data class Resolved(val providerName: String, val model: String, val provider: LlmProperties.Provider) {
        val apiKey get() = provider.apiKey?.takeIf { it.isNotBlank() }
    }

    private fun resolve(spec: String): Resolved {
        val providerName = spec.substringBefore(':', missingDelimiterValue = "")
        val model = spec.substringAfter(':', missingDelimiterValue = "")
        require(providerName.isNotBlank() && model.isNotBlank()) { "LLM spec must be '<provider>:<model>', got '$spec'" }
        val provider = properties.providers[providerName]
            ?: throw IllegalArgumentException(
                "Unknown LLM provider '$providerName' in '$spec'; configured: ${properties.providers.keys.sorted()}",
            )
        return Resolved(providerName, model, provider)
    }

    /** Embeddings for RAG (REVISION_PLAN step 14); only OpenAI-compatible providers (Ollama, LM Studio) serve them. */
    fun createEmbeddingModel(spec: String): EmbeddingModel {
        val (providerName, model, provider) = resolve(spec)
        require(provider.type == ProviderType.OPENAI_COMPATIBLE) {
            "Provider '$providerName' (${provider.type}) has no embeddings endpoint; use an OpenAI-compatible provider"
        }
        return OpenAiEmbeddingModel.builder()
            .baseUrl(requireNotNull(provider.baseUrl) { "No base-url configured for provider '$providerName'" })
            .apiKey(provider.apiKey?.takeIf { it.isNotBlank() } ?: "not-needed")
            .modelName(model)
            .timeout(provider.timeout ?: Duration.ofMinutes(10))
            .build()
    }

    fun create(spec: String): LlmClient {
        val resolved = resolve(spec)
        val (providerName, model, provider) = resolved
        val apiKey = resolved.apiKey

        return when (provider.type) {
            ProviderType.ANTHROPIC -> AnthropicLlmClient(
                modelId = model,
                apiKey = requireNotNull(apiKey) { "No API key configured for provider '$providerName'" },
                baseUrl = provider.baseUrl,
                timeout = provider.timeout ?: Duration.ofMinutes(5),
            )
            ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleLlmClient(
                modelId = model,
                baseUrl = requireNotNull(provider.baseUrl) { "No base-url configured for provider '$providerName'" },
                apiKey = apiKey,
                timeout = provider.timeout ?: Duration.ofMinutes(10),
            )
        }
    }
}
