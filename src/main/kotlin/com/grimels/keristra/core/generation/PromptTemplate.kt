package com.grimels.keristra.core.generation

import java.security.MessageDigest

data class RenderedPrompt(val system: String, val user: String)

/**
 * A versioned prompt pair from `resources/prompts/<track>/<version>.txt`, split into `=== system` and `=== user`
 * sections with `{{name}}` placeholders. [id] (`dmn/v1`) and [hash] (sha256 of the file) go into run logs.
 */
class PromptTemplate private constructor(
    val id: String,
    val hash: String,
    private val system: String,
    private val user: String,
) {

    fun render(values: Map<String, String>): RenderedPrompt {
        val missing = sortedSetOf<String>()
        fun fill(text: String) = PLACEHOLDER.replace(text) { match ->
            values[match.groupValues[1]] ?: match.value.also { missing += match.groupValues[1] }
        }
        val rendered = RenderedPrompt(fill(system), fill(user))
        require(missing.isEmpty()) { "Prompt $id has no value for $missing" }
        return rendered
    }

    companion object {
        private val PLACEHOLDER = Regex("\\{\\{(\\w+)\\}\\}")
        private val SECTION = Regex("^=== (system|user)\\s*$", RegexOption.MULTILINE)

        fun load(track: String, version: String): PromptTemplate {
            val path = "prompts/$track/$version.txt"
            val bytes = PromptTemplate::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Prompt resource not found: $path")
            val text = bytes.toString(Charsets.UTF_8)

            val headers = SECTION.findAll(text).toList()
            val sections = headers.mapIndexed { i, header ->
                val end = headers.getOrNull(i + 1)?.range?.first ?: text.length
                header.groupValues[1] to text.substring(header.range.last + 1, end).trim()
            }.toMap()
            require(sections.keys == setOf("system", "user")) { "$path must have exactly one '=== system' and one '=== user' section" }

            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return PromptTemplate("$track/$version", hash, sections.getValue("system"), sections.getValue("user"))
        }
    }
}
