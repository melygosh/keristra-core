package com.grimels.keristra.core.generation.tests

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.grimels.keristra.core.generation.DomainContextProvider
import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.stripCodeFences
import com.grimels.keristra.core.llm.LlmClient
import com.grimels.keristra.core.llm.LlmRequest
import com.grimels.keristra.core.llm.LlmResponse

/** Test cases in the shape the validators read: `{"object": {...}, "expected": [...]}` (plus optional name/description). */
data class TestSuite(val cases: List<Map<String, Any?>>, val promptId: String, val promptHash: String, val response: LlmResponse)

/**
 * The test track's verdict on a single input: [expected] in the validator format (`[]` = no rule applies).
 * [ambiguous] / [ambiguityNote]: the oracle says the verdict depends on an interpretation the rules do not state.
 */
data class Adjudicated(
    val expected: Any?, val promptId: String, val promptHash: String, val response: LlmResponse,
    val ambiguous: Boolean = false, val ambiguityNote: String? = null,
)

/**
 * The oracle marked this case as depending on an interpretation the rules do not state (`tests/v2a`, REVISION_PLAN
 * step 16): `"ambiguous": true` (or `"true"`). The case is still validated as usual.
 */
fun Map<String, Any?>.flaggedAmbiguous(): Boolean =
    this["ambiguous"].let { it == true || (it as? String)?.equals("true", ignoreCase = true) == true }

/** The oracle's note on a flagged case, if any. */
fun Map<String, Any?>.ambiguityNote(): String? = this["ambiguityNote"]?.toString()

/** The model answered, but not with a JSON array of objects; [response] keeps the raw answer for the run log. */
class TestSuiteParseException(message: String, val promptId: String, val response: LlmResponse, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The test track (the oracle): rules + domain schema -> test suite. Never sees the DMN track's output. */
class TestSuiteGenerator(
    internal val client: LlmClient,
    internal val prompt: PromptTemplate,
    private val contextProvider: DomainContextProvider,
    private val settings: GenerationSettings = GenerationSettings(),
    internal val adjudicationPrompt: PromptTemplate? = null,
) {

    /**
     * Re-adjudication (REVISION_PLAN step 10): the oracle judges one input on its own, with the rules and the schema
     * but never the DMN. Requires [adjudicationPrompt].
     */
    fun adjudicate(rules: String, domain: String, input: Map<String, Any?>, seedOffset: Long = 0): Adjudicated {
        val template = checkNotNull(adjudicationPrompt) { "No adjudication prompt configured for the test track" }
        val rendered = template.render(mapOf("rules" to rules, "schema" to contextProvider.schema(domain), "object" to GSON.toJson(input)))
        val response = client.complete(
            LlmRequest(rendered.system, rendered.user, settings.temperature, settings.seed?.plus(seedOffset), settings.maxTokens),
        )
        val verdict = parseCases(response).firstOrNull()
            ?: throw TestSuiteParseException("Adjudication returned no case", template.id, response)
        return Adjudicated(verdict["expected"], template.id, template.hash, response, verdict.flaggedAmbiguous(), verdict.ambiguityNote())
    }

    /** [seedOffset] is added to the configured seed, so retries and cycles do not replay one seeded sample. */
    fun generate(rules: String, domain: String, seedOffset: Long = 0): TestSuite {
        val rendered = prompt.render(mapOf("rules" to rules, "schema" to contextProvider.schema(domain)))
        val response = client.complete(
            LlmRequest(rendered.system, rendered.user, settings.temperature, settings.seed?.plus(seedOffset), settings.maxTokens),
        )
        return TestSuite(parseCases(response), prompt.id, prompt.hash, response)
    }

    private fun parseCases(response: LlmResponse): List<Map<String, Any?>> {
        val body = stripCodeFences(response.text)
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) throw TestSuiteParseException("No JSON array in the response", prompt.id, response)

        val elements: List<Any?> = try {
            GSON.fromJson(body.substring(start, end + 1), LIST_TYPE)
        } catch (e: JsonParseException) {
            throw TestSuiteParseException("Invalid JSON array: ${e.message}", prompt.id, response, e)
        }
        if (elements.any { it !is Map<*, *> }) throw TestSuiteParseException("Array elements must be JSON objects", prompt.id, response)
        // A case needs an input and an expectation (`expected: null` = no rule applies); bare input objects are not tests.
        val incomplete = elements.count { case -> case as Map<*, *>; "object" !in case || "expected" !in case }
        if (incomplete > 0) {
            throw TestSuiteParseException("$incomplete of ${elements.size} cases lack \"object\" or \"expected\"", prompt.id, response)
        }
        @Suppress("UNCHECKED_CAST")
        return elements as List<Map<String, Any?>>
    }

    private companion object {
        val GSON = Gson()
        val LIST_TYPE = object : TypeToken<List<Any?>>() {}.type
    }
}
