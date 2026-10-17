package com.grimels.keristra.core.generation

import com.grimels.keristra.core.llm.LlmClientFactory
import com.grimels.keristra.core.llm.LlmProperties
import com.grimels.keristra.core.llm.ProviderType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GenerationTracksTest {

    private val factory = LlmClientFactory(
        LlmProperties(mapOf("local" to LlmProperties.Provider(type = ProviderType.OPENAI_COMPATIBLE, baseUrl = "http://localhost:1/v1"))),
    )
    private val retriever = object : ContextRetriever {
        override val description = mapOf("kind" to "test")
        override fun retrieve(query: String) = emptyList<RetrievedSegment>()
    }
    private val requestedRag = mutableListOf<RagSettings>()
    private val tracks = GenerationTracks(factory, fixedSchema) { settings -> retriever.also { requestedRag += settings } }

    @Test
    fun `each track gets its own client, even for the same model`() {
        val pair = tracks.create(TrackConfig(model = "local:same-model"), TrackConfig(model = "local:same-model"))

        assertThat(pair.dmn.client).isNotSameAs(pair.tests.client)
        assertThat(pair.dmn.client.modelId).isEqualTo("same-model")
        assertThat(pair.tests.client.modelId).isEqualTo("same-model")
    }

    @Test
    fun `each track loads its own prompt version`() {
        val pair = tracks.create(TrackConfig(model = "local:a"), TrackConfig(model = "local:b"))

        assertThat(pair.dmn.prompt.id).isEqualTo("dmn/v1")
        assertThat(pair.tests.prompt.id).isEqualTo("tests/v1")
        assertThat(pair.tests.adjudicationPrompt!!.id).isEqualTo("tests-adjudicate/v1")
    }

    @Test
    fun `the adjudication prompt follows the test track's prompt version`() {
        val pair = tracks.create(TrackConfig(model = "local:a"), TrackConfig(model = "local:b", promptVersion = "v2"))

        assertThat(pair.tests.prompt.id).isEqualTo("tests/v2")
        assertThat(pair.tests.adjudicationPrompt!!.id).isEqualTo("tests-adjudicate/v2")
    }

    @Test
    fun `RAG settings on the DMN track give the DMN generator a retriever`() {
        val rag = RagSettings(embeddingModel = "local:bge-m3")
        val pair = tracks.create(TrackConfig(model = "local:a", rag = rag), TrackConfig(model = "local:b"))

        assertThat(pair.dmn.retriever).isSameAs(retriever)
        assertThat(requestedRag).containsExactly(rag)
    }

    @Test
    fun `without RAG settings the DMN generator has no retriever`() {
        assertThat(tracks.create(TrackConfig(model = "local:a"), TrackConfig(model = "local:b")).dmn.retriever).isNull()
    }

    @Test
    fun `RAG on the test track is refused - it would correlate the two tracks`() {
        assertThatThrownBy { tracks.create(TrackConfig(model = "local:a"), TrackConfig(model = "local:b", rag = RagSettings(embeddingModel = "local:e"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DMN track")
    }
}
