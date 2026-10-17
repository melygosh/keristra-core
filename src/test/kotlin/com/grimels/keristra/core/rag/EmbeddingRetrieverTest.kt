package com.grimels.keristra.core.rag

import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.output.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Deterministic embeddings: one dimension per keyword, so similarity is predictable. */
private class KeywordEmbeddings(private val keywords: List<String>) : EmbeddingModel {
    val calls = mutableListOf<Int>()
    override fun embedAll(segments: List<TextSegment>): Response<List<Embedding>> {
        calls += segments.size
        return Response.from(segments.map { s -> Embedding.from(keywords.map { k -> (if (k in s.text()) 1f else 0f) + 0.01f }) })
    }
}

class EmbeddingRetrieverTest {

    private val segments = listOf(
        CorpusSegment("a#0", "hitPolicy FIRST inputEntry"),
        CorpusSegment("b#0", "hitPolicy FIRST"),
        CorpusSegment("c#0", "xsd schema element"),
    )
    private val model = KeywordEmbeddings(listOf("hitPolicy", "FIRST", "inputEntry", "xsd"))

    @Test
    fun `returns the k most similar segments, best first, with ids and scores`() {
        val retriever = EmbeddingRetriever(model, segments, k = 2, minScore = 0.0, description = emptyMap())

        val found = retriever.retrieve("hitPolicy FIRST inputEntry")

        assertThat(found.map { it.id }).containsExactly("a#0", "b#0")
        assertThat(found[0].score).isGreaterThan(found[1].score)
        assertThat(found[0].text).isEqualTo("hitPolicy FIRST inputEntry")
    }

    @Test
    fun `minScore drops weak matches`() {
        val strict = EmbeddingRetriever(model, segments, k = 3, minScore = 0.99, description = emptyMap())

        assertThat(strict.retrieve("hitPolicy FIRST inputEntry").map { it.id }).containsExactly("a#0")
    }

    @Test
    fun `the corpus is embedded once, when the retriever is built`() {
        val retriever = EmbeddingRetriever(model, segments, k = 1, minScore = 0.0, description = emptyMap())
        retriever.retrieve("xsd")
        retriever.retrieve("FIRST")

        assertThat(model.calls).containsExactly(3, 1, 1)
    }
}
