package com.grimels.keristra.core.experiment

import com.grimels.keristra.core.context.DomainRegistry
import com.grimels.keristra.core.mutation.DmnMutator
import com.grimels.keristra.core.mutation.MutationAnalysis
import com.grimels.keristra.core.validation.CaseOutcome
import com.grimels.keristra.core.validation.StrictValidator
import com.grimels.keristra.core.validation.testEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The FOP rule sets delivered from Cowork (REVISION_PLAN step 13), confirmed on Camunda: every reference passes its
 * own ground truth (43 / 14 cases since 2026-10-18), the cross-version failures (E4 sanity baseline) are pinned, and
 * on the human ground truth only the accepted equivalent mutants survive.
 */
class FopRuleSetsTest {

    private val strict = StrictValidator(testEngine)
    private val repository = RuleSetRepository()
    private val versions = listOf("2024", "2025", "2026")

    private fun reference(set: String, version: String) =
        javaClass.getResourceAsStream("/rulesets/$set/$version/reference.dmn")!!.readBytes().toString(Charsets.UTF_8)

    private fun failures(set: String, dmnVersion: String, groundTruthVersion: String) =
        strict.validate(reference(set, dmnVersion), repository.load(set, groundTruthVersion, "uk").groundTruth)
            .cases.filter { it.outcome != CaseOutcome.PASS }

    @Test
    fun `every reference passes its own ground truth, schema check included`() {
        val schemaPaths = DomainRegistry().schemaPaths("fop")
        for ((set, size) in listOf("fop-eligibility" to 43, "fop-obligations" to 14)) {
            for (version in versions) {
                val ruleSet = repository.load(set, version, "uk")
                val report = strict.validate(reference(set, version), ruleSet.groundTruth, schemaPaths)

                assertThat(ruleSet.domain).isEqualTo("fop")
                assertThat(ruleSet.groundTruth).describedAs("$set/$version").hasSize(size)
                assertThat(report.tableError).describedAs("$set/$version").isNull()
                assertThat(report.accepted).describedAs("$set/$version").isTrue()
            }
        }
    }

    @Test
    fun `both languages share one ground truth per version`() {
        for (set in listOf("fop-eligibility", "fop-obligations")) {
            for (version in versions) {
                assertThat(repository.load(set, version, "en").groundTruth).isEqualTo(repository.load(set, version, "uk").groundTruth)
            }
        }
    }

    @Test
    fun `eligibility - cross-version failures come from the income limits only`() {
        val expected = mapOf(
            "2024" to mapOf("2025" to 6, "2026" to 12),
            "2025" to mapOf("2024" to 6, "2026" to 6),
            "2026" to mapOf("2024" to 12, "2025" to 6),
        )
        for ((dmn, row) in expected) for ((gt, count) in row) {
            val failing = failures("fop-eligibility", dmn, gt)
            assertThat(failing).describedAs("eligibility dmn=$dmn gt=$gt").hasSize(count)
            assertThat(failing).allMatch { it.outcome == CaseOutcome.MISMATCH }
        }
    }

    @Test
    fun `obligations - the 2025 military levy shows as EXTRA_OUTPUT against 2024 tests`() {
        val expected = mapOf(
            "2024" to mapOf("2025" to 11, "2026" to 11),
            "2025" to mapOf("2024" to 11, "2026" to 5),
            "2026" to mapOf("2024" to 11, "2025" to 5),
        )
        for ((dmn, row) in expected) for ((gt, count) in row) {
            assertThat(failures("fop-obligations", dmn, gt)).describedAs("obligations dmn=$dmn gt=$gt").hasSize(count)
        }

        val levyAgainst2024 = failures("fop-obligations", "2025", "2024")
        assertThat(levyAgainst2024.count { it.outcome == CaseOutcome.EXTRA_OUTPUT }).isEqualTo(9)
        assertThat(levyAgainst2024.filter { it.outcome == CaseOutcome.EXTRA_OUTPUT }.flatMap { it.extraOutputs.map { e -> e.key } }.toSet())
            .containsExactly("militaryLevy")
        assertThat(failures("fop-obligations", "2026", "2024").count { it.outcome == CaseOutcome.EXTRA_OUTPUT }).isEqualTo(6)
    }

    @Test
    fun `on the human ground truth only the accepted equivalent mutants survive, in every version`() {
        val equivalent = mapOf(
            "fop-eligibility" to listOf("RULE_SWAP:rule-5/rule-6", "RULE_SWAP:rule-9/rule-10"),
            "fop-obligations" to listOf("RULE_SWAP:rule-1/rule-2", "RULE_SWAP:rule-2/rule-3"),
        )
        // The former ground-truth gaps (docs/fop-equivalent-candidates.md), closed by the cases added on 2026-10-18.
        val formerGaps = mapOf(
            "fop-eligibility" to listOf("RULE_SWAP:rule-1/rule-2", "RULE_SWAP:rule-3/rule-4", "CONDITION_DROP:rule-10/1"),
            "fop-obligations" to listOf("CONDITION_DROP:rule-3/1"),
        )
        for ((set, ids) in equivalent) for (version in versions) {
            val groundTruth = repository.load(set, version, "uk").groundTruth
            val mutants = DmnMutator.mutants(reference(set, version))

            val survivors = mutants.filterNot { MutationAnalysis.isKilled(it, groundTruth, strict) }.map { it.id }

            assertThat(survivors).describedAs("$set/$version").containsExactlyInAnyOrderElementsOf(ids)
            assertThat(mutants.map { it.id }).describedAs("$set/$version").containsAll(formerGaps.getValue(set))
        }
    }
}
