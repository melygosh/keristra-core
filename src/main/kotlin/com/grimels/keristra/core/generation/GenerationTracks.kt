package com.grimels.keristra.core.generation

import com.grimels.keristra.core.generation.dmn.DmnGenerator
import com.grimels.keristra.core.generation.tests.TestSuiteGenerator
import com.grimels.keristra.core.llm.LlmClientFactory
import org.springframework.stereotype.Component

/** One track's setup: `<provider>:<model>` spec, prompt version and sampling settings. */
data class TrackConfig(val model: String, val promptVersion: String = "v1", val settings: GenerationSettings = GenerationSettings())

data class Tracks(val dmn: DmnGenerator, val tests: TestSuiteGenerator)

fun interface TracksProvider {
    fun create(dmn: TrackConfig, tests: TrackConfig): Tracks
}

/** Wires the two independent tracks; each gets its own LlmClient instance, even when both name the same model. */
@Component
class GenerationTracks(private val factory: LlmClientFactory, private val contextProvider: DomainContextProvider) : TracksProvider {

    override fun create(dmn: TrackConfig, tests: TrackConfig): Tracks = Tracks(
        dmn = DmnGenerator(factory.create(dmn.model), PromptTemplate.load("dmn", dmn.promptVersion), contextProvider, dmn.settings),
        tests = TestSuiteGenerator(factory.create(tests.model), PromptTemplate.load("tests", tests.promptVersion), contextProvider, tests.settings),
    )
}
