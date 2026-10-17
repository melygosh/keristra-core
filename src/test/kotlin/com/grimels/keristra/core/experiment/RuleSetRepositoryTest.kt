package com.grimels.keristra.core.experiment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RuleSetRepositoryTest {

    private val repository = RuleSetRepository()

    @Test
    fun `loads the V0 mortgage rule set`() {
        val ruleSet = repository.load("mortgage", "ambiguous", "en")

        assertThat(ruleSet.id).isEqualTo("mortgage/ambiguous")
        assertThat(ruleSet.domain).isEqualTo("mortgage")
        assertThat(ruleSet.language).isEqualTo("en")
        assertThat(ruleSet.rules).startsWith("Hit Policy: FIRST").contains("ManualReview")
        assertThat(ruleSet.meta["ambiguous"]).isEqualTo(true)
    }

    @Test
    fun `ground truth is in the validator format, null meaning no rule applies`() {
        val groundTruth = repository.load("mortgage", "ambiguous", "en").groundTruth

        assertThat(groundTruth).hasSize(8)
        assertThat(groundTruth).allSatisfy { assertThat((it["object"] as Map<*, *>).keys).contains("application") }
        assertThat(groundTruth[0]["expected"]).isEqualTo(listOf(mapOf("product" to "Fixed30")))
        assertThat(groundTruth[7]["expected"]).isNull()
    }

    @Test
    fun `hash identifies the exact files used`() {
        val first = repository.load("mortgage", "ambiguous", "en")
        val second = repository.load("mortgage", "ambiguous", "en")

        assertThat(first.hash).hasSize(64).isEqualTo(second.hash)
    }

    @Test
    fun `a missing language names the expected file`() {
        assertThatThrownBy { repository.load("mortgage", "ambiguous", "uk") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("rulesets/mortgage/ambiguous/rules.uk.txt")
    }

    @Test
    fun `an unknown rule set is an error`() {
        assertThatThrownBy { repository.load("fop", "2026", "uk") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
