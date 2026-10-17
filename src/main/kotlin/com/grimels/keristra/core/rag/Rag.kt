package com.grimels.keristra.core.rag

import com.grimels.keristra.core.generation.ContextRetriever
import com.grimels.keristra.core.generation.RagSettings
import com.grimels.keristra.core.generation.RetrievedSegment
import com.grimels.keristra.core.generation.RetrieverFactory
import com.grimels.keristra.core.llm.LlmClientFactory
import dev.langchain4j.data.document.Document
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import java.security.MessageDigest
import org.springframework.stereotype.Component

data class CorpusSegment(val id: String, val text: String)

/**
 * A frozen retrieval corpus read from classpath resources and split recursively into segments with ids
 * `<file>#<index>`. [hash] covers the files (names and bytes), not the segmentation, which is logged separately.
 */
class Corpus(val files: List<String>, val segments: List<CorpusSegment>, val hash: String) {
    companion object {
        /** The V0 corpus (REVISION_PLAN step 14): DMN/FEEL syntax and `users`-domain examples, no evaluated rule set. */
        val V0_FILES = listOf("DMNCustom.txt", "DMNCustom2.txt", "DMN.txt", "DMNSpec.xsd", "data-set/tuning.jsonl")

        fun load(files: List<String>, segmentSize: Int, segmentOverlap: Int): Corpus {
            val digest = MessageDigest.getInstance("SHA-256")
            val splitter = DocumentSplitters.recursive(segmentSize, segmentOverlap)
            val segments = files.flatMap { file ->
                val bytes = Corpus::class.java.getResourceAsStream("/$file")?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Corpus file not found on the classpath: $file")
                digest.update("$file\n".toByteArray()); digest.update(bytes); digest.update("\n".toByteArray())
                splitter.split(Document.from(bytes.toString(Charsets.UTF_8)))
                    .map { it.text() }
                    .filter { it.isNotBlank() }
                    .mapIndexed { i, text -> CorpusSegment("$file#$i", text) }
            }
            return Corpus(files, segments, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}

/**
 * Top-k retrieval over an in-memory store; the corpus is embedded once, when the retriever is built. Scores are
 * LangChain4J relevance scores, `(cosine + 1) / 2`, and [minScore] applies to them.
 */
class EmbeddingRetriever(
    private val model: EmbeddingModel,
    segments: List<CorpusSegment>,
    private val k: Int,
    private val minScore: Double,
    override val description: Map<String, Any?>,
) : ContextRetriever {

    private val store = InMemoryEmbeddingStore<TextSegment>().apply {
        val textSegments = segments.map { TextSegment.from(it.text, Metadata.from("id", it.id)) }
        if (textSegments.isNotEmpty()) addAll(model.embedAll(textSegments).content(), textSegments)
    }

    override fun retrieve(query: String): List<RetrievedSegment> {
        val request = EmbeddingSearchRequest.builder()
            .queryEmbedding(model.embed(query).content())
            .maxResults(k)
            .minScore(minScore)
            .build()
        return store.search(request).matches().map { RetrievedSegment(it.embedded().metadata().getString("id"), it.score(), it.embedded().text()) }
    }
}

@Component
class EmbeddingRetrieverFactory(private val llm: LlmClientFactory) : RetrieverFactory {
    override fun create(settings: RagSettings): ContextRetriever {
        val model = llm.createEmbeddingModel(settings.embeddingModel)
        val corpus = Corpus.load(settings.corpus.ifEmpty { Corpus.V0_FILES }, settings.segmentSize, settings.segmentOverlap)
        return EmbeddingRetriever(
            model, corpus.segments, settings.k, settings.minScore,
            description = mapOf(
                "embeddingModel" to settings.embeddingModel,
                "corpus" to corpus.files,
                "corpusHash" to corpus.hash,
                "segments" to corpus.segments.size,
                "segmentSize" to settings.segmentSize,
                "segmentOverlap" to settings.segmentOverlap,
                "k" to settings.k,
                "minScore" to settings.minScore,
                "score" to "relevance = (cosine + 1) / 2",
            ),
        )
    }
}
