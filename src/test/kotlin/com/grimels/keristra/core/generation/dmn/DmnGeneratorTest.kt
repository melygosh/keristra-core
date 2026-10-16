package com.grimels.keristra.core.generation.dmn

import com.grimels.keristra.core.generation.FakeLlmClient
import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.fixedSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DmnGeneratorTest {

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<definitions id="d"><decision id="decision"><text>application.a &gt; 1
and application.b</text></decision></definitions>"""

    private fun generator(reply: String, settings: GenerationSettings = GenerationSettings()) =
        FakeLlmClient(reply).let { it to DmnGenerator(it, PromptTemplate.load("dmn", "v1"), fixedSchema, settings) }

    @Test
    fun `sends the rendered dmn prompt with the track settings`() {
        val (client, generator) = generator(xml, GenerationSettings(temperature = 0.3, seed = 7, maxTokens = 1234))

        generator.generate(rules = "RULES-TEXT", domain = "mortgage")

        val request = client.requests.single()
        assertThat(request.system).contains("CAMUNDA DMN 1.3")
        assertThat(request.user).contains("RULES-TEXT").contains("""{"mortgage": {"creditScore": "number"}}""")
        assertThat(request.temperature).isEqualTo(0.3)
        assertThat(request.seed).isEqualTo(7)
        assertThat(request.maxTokens).isEqualTo(1234)
    }

    @Test
    fun `returns the xml with prompt provenance and the raw response`() {
        val (_, generator) = generator(xml)

        val result = generator.generate("rules", "mortgage")

        assertThat(result.xml).isEqualTo(xml)
        assertThat(result.promptId).isEqualTo("dmn/v1")
        assertThat(result.promptHash).hasSize(64)
        assertThat(result.response.text).isEqualTo(xml)
        assertThat(result.response.modelId).isEqualTo("fake-model")
    }

    @Test
    fun `strips code fences`() {
        val (_, generator) = generator("```xml\n$xml\n```")

        assertThat(generator.generate("rules", "mortgage").xml).isEqualTo(xml)
    }

    @Test
    fun `cuts prose around the xml`() {
        val (_, generator) = generator("Here is the table:\n$xml\nHope this helps.")

        assertThat(generator.generate("rules", "mortgage").xml).isEqualTo(xml)
    }

    @Test
    fun `keeps newlines inside FEEL text, unlike V0 which deleted them`() {
        val (_, generator) = generator(xml)

        assertThat(generator.generate("rules", "mortgage").xml).contains("&gt; 1\nand application.b")
    }

    @Test
    fun `returns non-xml output as is, so the validator reports PARSE_ERROR`() {
        val (_, generator) = generator("I cannot do that.")

        assertThat(generator.generate("rules", "mortgage").xml).isEqualTo("I cannot do that.")
    }
}
