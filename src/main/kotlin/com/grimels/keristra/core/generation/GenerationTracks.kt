package com.grimels.keristra.core.generation

import com.grimels.keristra.core.generation.dmn.DmnGenerator
import com.grimels.keristra.core.generation.tests.TestSuiteGenerator
import com.grimels.keristra.core.llm.LlmClientFactory
import org.springframework.stereotype.Component

/** One track's setup: `<provider>:<model>` spec, prompt version and sampling settings. */
data class TrackConfig(
    val model: String,
    val promptVersion: String = "v1",
    val settings: GenerationSettings = GenerationSettings(),
    val rag: RagSettings? = null,
)

data class Tracks(val dmn: DmnGenerator, val tests: TestSuiteGenerator)

interface TracksProvider {
    fun create(dmn: TrackConfig, tests: TrackConfig): Tracks

    /** The test track alone, for suite-only runs (REVISION_PLAN step 15): no DMN client is built. */
    fun tests(tests: TrackConfig): TestSuiteGenerator
}

/** Wires the two independent tracks; each gets its own LlmClient instance, even when both name the same model. */
@Component
class GenerationTracks(
    private val factory: LlmClientFactory,
    private val contextProvider: DomainContextProvider,
    private val retrievers: RetrieverFactory,
) : TracksProvider {

    override fun create(dmn: TrackConfig, tests: TrackConfig): Tracks {
        // The corpus is DMN/FEEL syntax; shared retrieval would also correlate the tracks (REVISION_PLAN step 14).
        require(tests.rag == null) { "RAG is only supported on the DMN track (REVISION_PLAN step 14)" }
        return Tracks(
            dmn = DmnGenerator(
                factory.create(dmn.model), PromptTemplate.load("dmn", dmn.promptVersion), contextProvider, dmn.settings,
                dmn.rag?.let(retrievers::create),
            ),
            tests = tests(tests),
        )
    }

    override fun tests(tests: TrackConfig): TestSuiteGenerator {
        require(tests.rag == null) { "RAG is only supported on the DMN track (REVISION_PLAN step 14)" }
        return TestSuiteGenerator(
            factory.create(tests.model), PromptTemplate.load("tests", tests.promptVersion), contextProvider, tests.settings,
            PromptTemplate.load("tests-adjudicate", tests.promptVersion),
        )
    }
}
