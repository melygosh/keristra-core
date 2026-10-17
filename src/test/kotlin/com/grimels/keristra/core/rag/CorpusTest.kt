package com.grimels.keristra.core.rag

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CorpusTest {

    @Test
    fun `the default corpus is the frozen V0 one`() {
        assertThat(Corpus.V0_FILES).containsExactly("DMNCustom.txt", "DMNCustom2.txt", "DMN.txt", "DMNSpec.xsd", "data-set/tuning.jsonl")
    }

    @Test
    fun `segments carry file-and-index ids and respect the segment size`() {
        val corpus = Corpus.load(Corpus.V0_FILES, segmentSize = 1024, segmentOverlap = 128)

        assertThat(corpus.segments).hasSizeGreaterThan(20)
        assertThat(corpus.segments.map { it.id }).doesNotHaveDuplicates().contains("DMN.txt#0", "DMNSpec.xsd#0", "data-set/tuning.jsonl#0")
        assertThat(corpus.segments).allMatch { it.text.length <= 1024 && it.text.isNotBlank() }
    }

    @Test
    fun `the hash identifies the files, not the segmentation`() {
        val a = Corpus.load(Corpus.V0_FILES, 1024, 128)
        val b = Corpus.load(Corpus.V0_FILES, 512, 0)
        val fewer = Corpus.load(listOf("DMN.txt"), 1024, 128)

        assertThat(a.hash).hasSize(64).isEqualTo(b.hash).isNotEqualTo(fewer.hash)
        assertThat(b.segments.size).isGreaterThan(a.segments.size)
    }

    @Test
    fun `a missing corpus file is an error`() {
        assertThatThrownBy { Corpus.load(listOf("nope.txt"), 1024, 128) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("nope.txt")
    }
}
