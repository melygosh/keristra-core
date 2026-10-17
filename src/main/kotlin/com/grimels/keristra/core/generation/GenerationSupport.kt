package com.grimels.keristra.core.generation

/** Domain context a generator puts into its prompt. RAG snippets would join the schema here (REVISION_PLAN 4.3). */
fun interface DomainContextProvider {
    fun schema(domain: String): String
}

/** Per-track sampling settings; see REVISION_PLAN F16 for which providers accept temperature and seed. */
data class GenerationSettings(val temperature: Double? = null, val seed: Long? = null, val maxTokens: Int = 8192)

/** The body of a fenced block (```xml / ```json / ```) if the text has one, else the text itself. */
internal fun stripCodeFences(text: String): String {
    val fenced = Regex("```[A-Za-z]*\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL).find(text)
    return (fenced?.groupValues?.get(1) ?: text).trim()
}

/** A corpus segment found for a query; [score] as the embedding store reports it. */
data class RetrievedSegment(val id: String, val score: Double, val text: String)

/** Retrieval for the DMN track (REVISION_PLAN step 14). [description] goes into `run_started`. */
interface ContextRetriever {
    val description: Map<String, Any?>
    fun retrieve(query: String): List<RetrievedSegment>
}

data class RagSettings(
    val embeddingModel: String,
    val corpus: List<String> = emptyList(),
    val k: Int = 6,
    val minScore: Double = 0.0,
    val segmentSize: Int = 1024,
    val segmentOverlap: Int = 128,
)

fun interface RetrieverFactory {
    fun create(settings: RagSettings): ContextRetriever
}
