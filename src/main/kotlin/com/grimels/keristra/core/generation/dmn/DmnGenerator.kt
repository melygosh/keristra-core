package com.grimels.keristra.core.generation.dmn

import com.grimels.keristra.core.generation.DomainContextProvider
import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.stripCodeFences
import com.grimels.keristra.core.llm.LlmClient
import com.grimels.keristra.core.llm.LlmRequest
import com.grimels.keristra.core.llm.LlmResponse

data class GeneratedDmn(val xml: String, val promptId: String, val promptHash: String, val response: LlmResponse)

/**
 * The DMN track: rules + domain schema -> DMN XML. Never sees the test track's output.
 * Only code fences and prose around the XML are removed; whitespace inside is kept (V0 deleted every newline,
 * which can glue FEEL tokens together). Output that is not XML is returned as is, for the validator to reject.
 */
class DmnGenerator(
    internal val client: LlmClient,
    internal val prompt: PromptTemplate,
    private val contextProvider: DomainContextProvider,
    private val settings: GenerationSettings = GenerationSettings(),
) {

    fun generate(rules: String, domain: String): GeneratedDmn {
        val rendered = prompt.render(mapOf("rules" to rules, "schema" to contextProvider.schema(domain)))
        val response = client.complete(
            LlmRequest(rendered.system, rendered.user, settings.temperature, settings.seed, settings.maxTokens),
        )
        return GeneratedDmn(extractXml(response.text), prompt.id, prompt.hash, response)
    }

    private fun extractXml(text: String): String {
        val body = stripCodeFences(text)
        val start = listOf(body.indexOf("<?xml"), body.indexOf("<definitions")).filter { it >= 0 }.minOrNull() ?: return body
        val end = body.lastIndexOf('>')
        return if (end > start) body.substring(start, end + 1) else body
    }
}
