package com.grimels.keristra.core.generation.tests

import com.grimels.keristra.core.generation.FakeLlmClient
import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.fixedSchema
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TestSuiteGeneratorTest {

    private val suiteJson = """[{"name": "t1", "object": {"application": {"creditScore": 720}}, "expected": [{"product": "ARM7/1"}]},
        {"name": "t2", "object": {"application": {"creditScore": 600}}, "expected": []}]"""

    private fun generator(reply: String, settings: GenerationSettings = GenerationSettings()) =
        FakeLlmClient(reply).let {
            it to TestSuiteGenerator(it, PromptTemplate.load("tests", "v1"), fixedSchema, settings, PromptTemplate.load("tests-adjudicate", "v1"))
        }

    @Test
    fun `sends the rendered tests prompt with the track settings`() {
        val (client, generator) = generator(suiteJson, GenerationSettings(temperature = 0.9, seed = 3, maxTokens = 999))

        generator.generate(rules = "RULES-TEXT", domain = "mortgage")

        val request = client.requests.single()
        assertThat(request.system).contains("JSON array")
        assertThat(request.user).contains("RULES-TEXT").contains("""{"mortgage": {"creditScore": "number"}}""")
        assertThat(request.temperature).isEqualTo(0.9)
        assertThat(request.seed).isEqualTo(3)
        assertThat(request.maxTokens).isEqualTo(999)
    }

    @Test
    fun `seed offset is added to the configured seed`() {
        val (client, generator) = generator(suiteJson, GenerationSettings(seed = 42))

        generator.generate("rules", "mortgage", seedOffset = 3002)

        assertThat(client.requests.single().seed).isEqualTo(3044)
    }

    @Test
    fun `parses the array into test cases with prompt provenance`() {
        val (_, generator) = generator(suiteJson)

        val suite = generator.generate("rules", "mortgage")

        assertThat(suite.cases).hasSize(2)
        assertThat(suite.cases[0]["name"]).isEqualTo("t1")
        assertThat(suite.cases[0]["expected"]).isEqualTo(listOf(mapOf("product" to "ARM7/1")))
        assertThat(suite.promptId).isEqualTo("tests/v1")
        assertThat(suite.promptHash).hasSize(64)
        assertThat(suite.response.text).isEqualTo(suiteJson)
    }

    @Test
    fun `strips code fences and prose around the array`() {
        val (_, fenced) = generator("```json\n$suiteJson\n```")
        val (_, chatty) = generator("Sure! Here are the cases:\n$suiteJson\nLet me know.")

        assertThat(fenced.generate("rules", "mortgage").cases).hasSize(2)
        assertThat(chatty.generate("rules", "mortgage").cases).hasSize(2)
    }

    @Test
    fun `unparseable output raises TestSuiteParseException carrying the raw response`() {
        val (_, generator) = generator("[{\"name\": \"t1\", \"object\": ")

        assertThatThrownBy { generator.generate("rules", "mortgage") }
            .isInstanceOfSatisfying(TestSuiteParseException::class.java) {
                assertThat(it.response.text).isEqualTo("[{\"name\": \"t1\", \"object\": ")
                assertThat(it.promptId).isEqualTo("tests/v1")
            }
    }

    @Test
    fun `array of non-objects is rejected`() {
        val (_, generator) = generator("[1, 2, 3]")

        assertThatThrownBy { generator.generate("rules", "mortgage") }.isInstanceOf(TestSuiteParseException::class.java)
    }

    @Test
    fun `adjudicate sends one input with the rules and schema, never a table, and returns its expected output`() {
        val (client, generator) = generator("""[{"object": {"application": {"creditScore": 720}}, "expected": [{"product": "ARM7/1"}]}]""",
            GenerationSettings(temperature = 0.2, seed = 10))

        val verdict = generator.adjudicate("RULES-TEXT", "mortgage", mapOf("application" to mapOf("creditScore" to 720.0)), seedOffset = 5)

        val request = client.requests.single()
        assertThat(request.user).contains("RULES-TEXT").contains("\"creditScore\":720").doesNotContain("<definitions")
        assertThat(request.seed).isEqualTo(15)
        assertThat(verdict.expected).isEqualTo(listOf(mapOf("product" to "ARM7/1")))
        assertThat(verdict.promptId).isEqualTo("tests-adjudicate/v1")
        assertThat(verdict.response.text).contains("ARM7/1")
    }

    @Test
    fun `adjudicate maps no-rule-applies to an empty expectation`() {
        val (_, generator) = generator("""[{"object": {}, "expected": []}]""")

        assertThat(generator.adjudicate("rules", "mortgage", emptyMap()).expected).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `an unparseable verdict raises TestSuiteParseException`() {
        val (_, generator) = generator("I think it is ARM7/1")

        assertThatThrownBy { generator.adjudicate("rules", "mortgage", emptyMap()) }.isInstanceOf(TestSuiteParseException::class.java)
    }
}
