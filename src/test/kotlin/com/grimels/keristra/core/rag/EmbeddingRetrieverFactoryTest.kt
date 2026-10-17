package com.grimels.keristra.core.rag

import com.grimels.keristra.core.generation.RagSettings
import com.grimels.keristra.core.llm.LlmClientFactory
import com.grimels.keristra.core.llm.LlmProperties
import com.grimels.keristra.core.llm.ProviderType
import com.grimels.keristra.core.llm.StubLlmServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EmbeddingRetrieverFactoryTest {

    /** OpenAI-compatible /embeddings: one 3-d vector per input, depending on whether the text mentions "FIRST". */
    private fun embeddings(body: com.google.gson.JsonObject): String {
        val inputs = body["input"].let { if (it.isJsonArray) it.asJsonArray.map { e -> e.asString } else listOf(it.asString) }
        val data = inputs.mapIndexed { i, text ->
            val v = if ("FIRST" in text) "[1.0, 0.0, 0.1]" else "[0.0, 1.0, 0.1]"
            """{"object": "embedding", "index": $i, "embedding": $v}"""
        }
        return """{"object": "list", "data": [${data.joinToString(",")}], "model": "${body["model"].asString}", "usage": {"prompt_tokens": 1, "total_tokens": 1}}"""
    }

    private fun factory(baseUrl: String) = EmbeddingRetrieverFactory(
        LlmClientFactory(
            LlmProperties(
                mapOf(
                    "local" to LlmProperties.Provider(type = ProviderType.OPENAI_COMPATIBLE, baseUrl = "$baseUrl/v1"),
                    "anthropic" to LlmProperties.Provider(type = ProviderType.ANTHROPIC, apiKey = "k"),
                ),
            ),
        ),
    )

    @Test
    fun `embeds the corpus through the local OpenAI-compatible endpoint and describes itself for the run log`() {
        StubLlmServer("{}", ::embeddings).use { server ->
            val retriever = factory(server.baseUrl).create(RagSettings(embeddingModel = "local:bge-m3", corpus = listOf("DMNCustom2.txt"), k = 2))

            val found = retriever.retrieve("Hit policy FIRST")

            assertThat(server.requests.map { it.path }.toSet()).containsExactly("/v1/embeddings")
            assertThat(server.requests.first().body["model"].asString).isEqualTo("bge-m3")
            assertThat(found).hasSizeLessThanOrEqualTo(2)
            assertThat(retriever.description)
                .containsEntry("embeddingModel", "local:bge-m3")
                .containsEntry("corpus", listOf("DMNCustom2.txt"))
                .containsEntry("k", 2)
                .containsKeys("corpusHash", "segments", "minScore", "segmentSize", "segmentOverlap")
        }
    }

    @Test
    fun `Anthropic has no embeddings endpoint`() {
        assertThatThrownBy { factory("http://127.0.0.1:1").create(RagSettings(embeddingModel = "anthropic:claude-x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("embeddings")
    }
}
