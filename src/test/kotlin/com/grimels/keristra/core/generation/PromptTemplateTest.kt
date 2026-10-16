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
}
