package com.grimels.keristra.core.generation.dmn

import com.grimels.keristra.core.generation.FakeLlmClient
import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.ContextRetriever
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.RetrievedSegment
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
    fun `seed offset is added to the configured seed, and no seed stays no seed`() {
        val (seeded, seededGenerator) = generator(xml, GenerationSettings(seed = 7))
        val (unseeded, unseededGenerator) = generator(xml, GenerationSettings(seed = null))

        seededGenerator.generate("rules", "mortgage", seedOffset = 2001)
        unseededGenerator.generate("rules", "mortgage", seedOffset = 2001)

        assertThat(seeded.requests.single().seed).isEqualTo(2008)
        assertThat(unseeded.requests.single().seed).isNull()
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

    // --- RAG on the DMN track (REVISION_PLAN step 14) ---

    private class FixedRetriever(private val segments: List<RetrievedSegment>) : ContextRetriever {
        val queries = mutableListOf<String>()
        override val description = mapOf("kind" to "fixed")
        override fun retrieve(query: String) = segments.also { queries += query }
    }

    private fun ragGenerator(retriever: ContextRetriever?) =
        FakeLlmClient(xml).let { it to DmnGenerator(it, PromptTemplate.load("dmn", "v1"), fixedSchema, GenerationSettings(), retriever) }

    @Test
    fun `with a retriever the rules are the query and segments are appended as LangChain4J injected them in V0`() {
        val retriever = FixedRetriever(listOf(RetrievedSegment("DMN.txt#3", 0.91, "SEG-A"), RetrievedSegment("DMNSpec.xsd#0", 0.87, "SEG-B")))
        val (plain, plainGenerator) = ragGenerator(null)
        val (client, generator) = ragGenerator(retriever)

        plainGenerator.generate("RULES-TEXT", "mortgage")
        val result = generator.generate("RULES-TEXT", "mortgage")

        assertThat(retriever.queries).containsExactly("RULES-TEXT")
        assertThat(client.requests.single().user)
            .isEqualTo(plain.requests.single().user + "\n\nAnswer using the following information:\nSEG-A\n\nSEG-B")
        assertThat(result.retrieved!!.map { it.id }).containsExactly("DMN.txt#3", "DMNSpec.xsd#0")
    }

    @Test
    fun `an empty retrieval adds nothing to the prompt but is recorded as empty, not as RAG off`() {
        val (plain, plainGenerator) = ragGenerator(null)
        val (client, generator) = ragGenerator(FixedRetriever(emptyList()))

        val off = plainGenerator.generate("rules", "mortgage")
        val empty = generator.generate("rules", "mortgage")

        assertThat(client.requests.single().user).isEqualTo(plain.requests.single().user)
        assertThat(off.retrieved).isNull()
        assertThat(empty.retrieved).isNotNull().isEmpty()
    }
}
