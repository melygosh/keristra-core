package com.grimels.keristra.core.llm

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.grimels.keristra.core.validation.StrictValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * REVISION_PLAN step 3 acceptance: one test suite from a local OpenAI-compatible model and one DMN from
 * Anthropic in the same run. Calls real models (costs money), so it only runs with KERISTRA_LLM_SMOKE=true:
 *
 *   KERISTRA_LLM_SMOKE=true ./gradlew test --tests '*LlmSmokeTest'
 *
 * Model specs can be overridden with KERISTRA_SMOKE_DMN_MODEL / KERISTRA_SMOKE_TEST_MODEL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KERISTRA_LLM_SMOKE", matches = "true")
class LlmSmokeTest {

    @Autowired
    private lateinit var factory: LlmClientFactory

    @Autowired
    private lateinit var strictValidator: StrictValidator

    private val logger = LoggerFactory.getLogger(LlmSmokeTest::class.java)

    private val rules = """
        Hit policy is FIRST. Output column: product (string).
        1. If application.fixedPreference is true, product is "Fixed30".
        2. Otherwise, if application.creditScore is at least 700 and application.horizonYears is below 7, product is "ARM7/1".
        3. Otherwise product is "Fixed30".
    """.trimIndent()

    private val schema = """{"application": {"fixedPreference": "boolean", "creditScore": "number", "horizonYears": "number"}}"""

    @Test
    fun `local model writes the test suite, Anthropic writes the DMN`() {
        // claude-sonnet-4-20250514 (the V0 model) is no longer served: the API answers not_found_error.
        val dmnClient = factory.create(System.getenv("KERISTRA_SMOKE_DMN_MODEL") ?: "anthropic:claude-sonnet-5-5")
        val testClient = factory.create(System.getenv("KERISTRA_SMOKE_TEST_MODEL") ?: "local:gemma4:26b")

        val tests = testClient.complete(
            LlmRequest(
                system = "You write test cases for DMN decision tables. Reply with a JSON array only, no prose, no code fences.",
                user = """Write 6 test cases for the rules below. Each element: {"object": <input matching the schema>, "expected": [{"product": "<value>"}]}.
                    |<schema>$schema</schema>
                    |<rules>$rules</rules>""".trimMargin(),
                temperature = 0.2,
                seed = 42,
                maxTokens = 4000,
            ),
        )
        val dmn = dmnClient.complete(
            LlmRequest(
                system = "You write Camunda DMN 1.3 XML. Reply with the XML only, no prose, no code fences. The decision id must be \"decision\".",
                user = "Write a decision table for these rules. FEEL input expressions must use the schema paths.\n<schema>$schema</schema>\n<rules>$rules</rules>",
                // No temperature: current Claude models (Sonnet/Opus/Haiku 5.x) reject non-default sampling values.
                // Room for adaptive thinking, which is on by default there and counts towards max_tokens.
                maxTokens = 16000,
            ),
        )
        for ((track, response) in listOf("tests" to tests, "dmn" to dmn)) {
            logger.info("[$track] model=${response.modelId} in=${response.inputTokens} out=${response.outputTokens} latency=${response.latencyMs}ms")
        }

        val testCases: List<Map<String, Any?>> = Gson().fromJson(stripFences(tests.text), object : TypeToken<List<Map<String, Any?>>>() {}.type)
        assertThat(testCases).isNotEmpty()

        val report = strictValidator.validate(stripFences(dmn.text), testCases)
        logger.info("Strict report: tableError=${report.tableError} accepted=${report.accepted} counts=${report.counts}")
        assertThat(report.tableError).describedAs(report.tableErrorMessage).isNull()
    }

    private fun stripFences(text: String) =
        text.trim().removePrefix("```json").removePrefix("```xml").removePrefix("```").removeSuffix("```").trim()
}
