package com.grimels.keristra.core.generation

import com.grimels.keristra.core.ai.anthropic.AnthropicDMNService
import com.grimels.keristra.core.ai.anthropic.V0Prompts
import com.grimels.keristra.core.util.SchemaResolver
import dev.langchain4j.service.SystemMessage
import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PromptTemplateTest {

    private val rules = "Hit policy is FIRST.\n1. If application.fixedPreference is true, product is \"Fixed30\"."
    private val schema = SchemaResolver.getSchema("mortgage")

    @Test
    fun `id names the track and version, hash is the sha256 of the resource`() {
        val template = PromptTemplate.load("dmn", "v1")

        val bytes = javaClass.getResourceAsStream("/prompts/dmn/v1.txt")!!.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertThat(template.id).isEqualTo("dmn/v1")
        assertThat(template.hash).isEqualTo(sha256)
    }

    @Test
    fun `render substitutes every placeholder`() {
        val prompt = PromptTemplate.load("tests", "v1").render(mapOf("rules" to "R-TEXT", "schema" to "S-TEXT"))

        assertThat(prompt.user).contains("R-TEXT").contains("S-TEXT").doesNotContain("{{")
        assertThat(prompt.system).doesNotContain("{{")
    }

    @Test
    fun `render fails when a placeholder has no value`() {
        assertThatThrownBy { PromptTemplate.load("dmn", "v1").render(mapOf("rules" to "R")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("schema")
    }

    @Test
    fun `unknown version fails with the resource path`() {
        assertThatThrownBy { PromptTemplate.load("dmn", "v999") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("prompts/dmn/v999.txt")
    }

    @Test
    fun `dmn v1 is the V0 prompt pair, whitespace aside`() {
        val prompt = PromptTemplate.load("dmn", "v1").render(mapOf("rules" to rules, "schema" to schema))

        assertThat(normalize(prompt.system)).isEqualTo(normalize(v0SystemMessage("generateDMNTable")))
        assertThat(normalize(prompt.user)).isEqualTo(normalize(V0Prompts.dmnUserMessage(rules, schema)))
    }

    @Test
    fun `tests v1 is the V0 prompt pair, whitespace aside`() {
        val prompt = PromptTemplate.load("tests", "v1").render(mapOf("rules" to rules, "schema" to schema))

        assertThat(normalize(prompt.system)).isEqualTo(normalize(v0SystemMessage("generateEntities")))
        assertThat(normalize(prompt.user)).isEqualTo(normalize(V0Prompts.testsUserMessage(rules, schema)))
    }

    private fun v0SystemMessage(method: String): String =
        AnthropicDMNService::class.java.getMethod(method, String::class.java)
            .getAnnotation(SystemMessage::class.java).value.joinToString("\n")

    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

    @Test
    fun `the adjudication prompt takes rules, schema and one object`() {
        val prompt = PromptTemplate.load("tests-adjudicate", "v1").render(mapOf("rules" to "R", "schema" to "S", "object" to "{\"a\":1}"))

        assertThat(prompt.user).contains("R").contains("S").contains("{\"a\":1}")
        assertThat(prompt.system).contains("do not see the table")
    }

    @Test
    fun `tests v2 is v1 plus the requirement to name every output`() {
        val values = mapOf("rules" to rules, "schema" to schema)
        val v1 = PromptTemplate.load("tests", "v1").render(values)
        val v2 = PromptTemplate.load("tests", "v2").render(values)

        assertThat(v2.user).isEqualTo(v1.user)
        assertThat(v2.system).startsWith(v1.system).contains("EVERY output variable")
        assertThat(v2.system.removePrefix(v1.system)).contains("[]")
    }

    @Test
    fun `adjudication v2 also requires every output`() {
        val values = mapOf("rules" to "R", "schema" to "S", "object" to "{}")
        val v1 = PromptTemplate.load("tests-adjudicate", "v1").render(values)
        val v2 = PromptTemplate.load("tests-adjudicate", "v2").render(values)

        assertThat(v2.user).isEqualTo(v1.user)
        assertThat(v2.system).startsWith(v1.system).contains("EVERY output variable")
    }
}
