package com.grimels.keristra.core.generation.dmn

import com.grimels.keristra.core.generation.ContextRetriever
import com.grimels.keristra.core.generation.DomainContextProvider
import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.RetrievedSegment
import com.grimels.keristra.core.generation.stripCodeFences
import com.grimels.keristra.core.llm.LlmClient
import com.grimels.keristra.core.llm.LlmRequest
import com.grimels.keristra.core.llm.LlmResponse

data class GeneratedDmn(
    val xml: String,
    val promptId: String,
    val promptHash: String,
    val response: LlmResponse,
    /** Null = RAG off; empty = RAG on but nothing passed the threshold. */
    val retrieved: List<RetrievedSegment>? = null,
)

/**
 * The DMN track: rules + domain schema -> DMN XML. Never sees the test track's output.
 * With a [retriever] (RAG, REVISION_PLAN step 14) the rules text is the query and the segments are appended to the
 * user message. Only code fences and prose around the XML are removed; whitespace inside is kept (V0 deleted every newline,
 * which can glue FEEL tokens together). Output that is not XML is returned as is, for the validator to reject.
 */
class DmnGenerator(
    internal val client: LlmClient,
    internal val prompt: PromptTemplate,
    private val contextProvider: DomainContextProvider,
    private val settings: GenerationSettings = GenerationSettings(),
    internal val retriever: ContextRetriever? = null,
) {

    /** [seedOffset] is added to the configured seed, so retries and cycles do not replay one seeded sample. */
    fun generate(rules: String, domain: String, seedOffset: Long = 0): GeneratedDmn {
        val rendered = prompt.render(mapOf("rules" to rules, "schema" to contextProvider.schema(domain)))
        val retrieved = retriever?.retrieve(rules)
        val user = if (retrieved.isNullOrEmpty()) rendered.user else rendered.user + RAG_HEADER + retrieved.joinToString("\n\n") { it.text }
        val response = client.complete(
            LlmRequest(rendered.system, user, settings.temperature, settings.seed?.plus(seedOffset), settings.maxTokens),
        )
        return GeneratedDmn(extractXml(response.text), prompt.id, prompt.hash, response, retrieved)
    }

    private companion object {
        /** How LangChain4J's DefaultContentInjector appended retrieved content in V0's AiServices pipeline. */
        const val RAG_HEADER = "\n\nAnswer using the following information:\n"
    }

    private fun extractXml(text: String): String {
        val body = stripCodeFences(text)
        val start = listOf(body.indexOf("<?xml"), body.indexOf("<definitions")).filter { it >= 0 }.minOrNull() ?: return body
        val end = body.lastIndexOf('>')
        return if (end > start) body.substring(start, end + 1) else body
    }
}
