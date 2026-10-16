package com.grimels.keristra.core.generation

import com.grimels.keristra.core.llm.LlmClientFactory
import com.grimels.keristra.core.llm.LlmProperties
import com.grimels.keristra.core.llm.ProviderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GenerationTracksTest {

    private val factory = LlmClientFactory(
        LlmProperties(mapOf("local" to LlmProperties.Provider(type = ProviderType.OPENAI_COMPATIBLE, baseUrl = "http://localhost:1/v1"))),
    )
    private val tracks = GenerationTracks(factory, fixedSchema)

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
    }
}
