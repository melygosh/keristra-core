package com.grimels.keristra.core.generation

import com.grimels.keristra.core.util.SchemaResolver
import org.springframework.stereotype.Component

/** Domain context a generator puts into its prompt. RAG snippets would join the schema here (REVISION_PLAN 4.3). */
fun interface DomainContextProvider {
    fun schema(domain: String): String
}

/** Until DomainRegistry exists (REVISION_PLAN 4.3), schemas come from the V0 resolver. */
@Component
class SchemaResolverContextProvider : DomainContextProvider {
    override fun schema(domain: String): String = SchemaResolver.getSchema(domain)
}

/** Per-track sampling settings; see REVISION_PLAN F16 for which providers accept temperature and seed. */
data class GenerationSettings(val temperature: Double? = null, val seed: Long? = null, val maxTokens: Int = 8192)

/** The body of a fenced block (```xml / ```json / ```) if the text has one, else the text itself. */
internal fun stripCodeFences(text: String): String {
    val fenced = Regex("```[A-Za-z]*\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL).find(text)
    return (fenced?.groupValues?.get(1) ?: text).trim()
}
