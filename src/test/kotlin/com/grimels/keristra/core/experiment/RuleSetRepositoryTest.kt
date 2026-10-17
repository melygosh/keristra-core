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

        assertThat(groundTruth).hasSize(45)
        assertThat(groundTruth.take(8)).allMatch { it["category"] == "original-v0" }
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
        assertThatThrownBy { repository.load("mortgage", "ambiguous", "de") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("rulesets/mortgage/ambiguous/rules.de.txt")
    }

    @Test
    fun `the language picks the rule text and changes the hash`() {
        val en = repository.load("mortgage", "ambiguous", "en")
        val uk = repository.load("mortgage", "ambiguous", "uk")

        assertThat(uk.rules).isNotEqualTo(en.rules)
        assertThat(uk.hash).isNotEqualTo(en.hash)
        assertThat(uk.groundTruth).isEqualTo(en.groundTruth)
    }

    @Test
    fun `the disambiguated variant differs from the ambiguous one only in its text (E3 control)`() {
        val ambiguous = repository.load("mortgage", "ambiguous", "en")
        val disambiguated = repository.load("mortgage", "disambiguated", "en")

        assertThat(disambiguated.meta["ambiguous"]).isEqualTo(false)
        assertThat(disambiguated.domain).isEqualTo("mortgage")
        assertThat(disambiguated.groundTruth).isEqualTo(ambiguous.groundTruth)
        assertThat(disambiguated.rules).isNotEqualTo(ambiguous.rules).contains("takes precedence")
    }

    @Test
    fun `an unknown rule set is an error`() {
        assertThatThrownBy { repository.load("fop", "2026", "uk") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
