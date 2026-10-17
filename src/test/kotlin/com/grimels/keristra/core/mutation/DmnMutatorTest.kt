package com.grimels.keristra.core.mutation

import com.grimels.keristra.core.experiment.RuleSetRepository
import com.grimels.keristra.core.mutation.MutationOperator.BOUNDARY_FLIP
import com.grimels.keristra.core.mutation.MutationOperator.CONDITION_DROP
import com.grimels.keristra.core.mutation.MutationOperator.HIT_POLICY_CHANGE
import com.grimels.keristra.core.mutation.MutationOperator.OUTPUT_SWAP
import com.grimels.keristra.core.mutation.MutationOperator.RULE_DROP
import com.grimels.keristra.core.mutation.MutationOperator.RULE_SWAP
import com.grimels.keristra.core.mutation.MutationOperator.THRESHOLD_SHIFT
import com.grimels.keristra.core.validation.DmnTable
import com.grimels.keristra.core.validation.SemanticHash
import com.grimels.keristra.core.validation.StrictValidator
import com.grimels.keristra.core.validation.testEngine
import org.assertj.core.api.Assertions.assertThat
import org.camunda.bpm.model.dmn.Dmn
import org.camunda.bpm.model.dmn.instance.Decision
import org.camunda.bpm.model.dmn.instance.DecisionTable
import org.junit.jupiter.api.Test

/** On the draft mortgage/ambiguous reference: FIRST, 7 inputs, 6 rules, outputs Fixed30 / ARM7/1 / ManualReview. */
class DmnMutatorTest {

    private val ruleSet = RuleSetRepository().load("mortgage", "ambiguous", "en")
    private val reference = javaClass.getResourceAsStream("/rulesets/mortgage/ambiguous/reference.dmn")!!.readBytes().toString(Charsets.UTF_8)
    private val mutants = DmnMutator.mutants(reference)
    private val strict = StrictValidator(testEngine)

    private fun of(operator: MutationOperator) = mutants.filter { it.operator == operator }

    private fun table(xml: String) =
        Dmn.readModelFromStream(xml.byteInputStream()).getModelElementById<Decision>("decision").expression as DecisionTable

    private fun entry(xml: String, ruleId: String, input: Int) =
        table(xml).rules.single { it.id == ruleId }.inputEntries.toList()[input].text.textContent

    private fun only(operator: MutationOperator, ruleId: String, before: String, after: String) =
        of(operator).single { it.region.ruleIds == listOf(ruleId) && it.region.before == before && it.region.after == after }

    @Test
    fun `the draft reference passes its ground truth`() {
        assertThat(strict.validate(reference, ruleSet.groundTruth).accepted).isTrue()
    }

    @Test
    fun `every mutant parses, changes the logic and has a unique id`() {
        assertThat(mutants).hasSize(54)
        assertThat(mutants.map { it.id }).doesNotHaveDuplicates()
        val referenceHash = SemanticHash.of(reference)
        for (mutant in mutants) {
            DmnTable.parse(testEngine, mutant.xml)
            assertThat(SemanticHash.of(mutant.xml)).describedAs(mutant.id).isNotEqualTo(referenceHash)
        }
    }

    @Test
    fun `THRESHOLD_SHIFT moves every numeric literal of an input entry by one unit both ways`() {
        assertThat(of(THRESHOLD_SHIFT)).hasSize(12)

        val up = only(THRESHOLD_SHIFT, "rule-3", ">= 720", ">= 721")
        only(THRESHOLD_SHIFT, "rule-3", ">= 720", ">= 719")
        assertThat(up.region.input).isEqualTo("application.creditScore")
        assertThat(entry(up.xml, "rule-3", 5)).isEqualTo(">= 721")
        assertThat(entry(up.xml, "rule-3", 4)).isEqualTo("<= 80")
    }

    @Test
    fun `THRESHOLD_SHIFT uses one percent for decimal literals`() {
        val decimal = reference.replace("&lt;= 80", "&lt;= 80.5")

        val shifted = DmnMutator.mutants(decimal, setOf(THRESHOLD_SHIFT)).filter { it.region.before == "<= 80.5" }

        assertThat(shifted.map { it.region.after }).containsExactlyInAnyOrder("<= 81.305", "<= 79.695")
    }

    @Test
    fun `BOUNDARY_FLIP toggles strictness of each comparison`() {
        assertThat(of(BOUNDARY_FLIP)).hasSize(6)

        only(BOUNDARY_FLIP, "rule-3", "<= 7", "< 7")
        only(BOUNDARY_FLIP, "rule-4", "< 640", "<= 640")
        only(BOUNDARY_FLIP, "rule-5", "> 50", ">= 50")
        only(BOUNDARY_FLIP, "rule-3", ">= 720", "> 720")
    }

    @Test
    fun `BOUNDARY_FLIP toggles each bracket of a range`() {
        val ranged = reference.replace("&lt;= 80", "[60..80]")

        val flips = DmnMutator.mutants(ranged, setOf(BOUNDARY_FLIP)).filter { it.region.before == "[60..80]" }

        assertThat(flips.map { it.region.after }).containsExactlyInAnyOrder("(60..80]", "[60..80)")
    }

    @Test
    fun `RULE_SWAP swaps adjacent rules under FIRST`() {
        assertThat(of(RULE_SWAP)).hasSize(5)

        val swap = of(RULE_SWAP).single { it.region.ruleIds == listOf("rule-3", "rule-4") }
        assertThat(table(swap.xml).rules.map { it.id }).containsExactly("rule-1", "rule-2", "rule-4", "rule-3", "rule-5", "rule-6")
    }

    @Test
    fun `RULE_SWAP is not applied where rule order has no effect`() {
        val unique = reference.replace("hitPolicy=\"FIRST\"", "hitPolicy=\"UNIQUE\"")

        assertThat(DmnMutator.mutants(unique, setOf(RULE_SWAP))).isEmpty()
    }

    @Test
    fun `RULE_DROP removes each rule, a default rule included`() {
        assertThat(of(RULE_DROP)).hasSize(6)
        val drop = of(RULE_DROP).single { it.region.ruleIds == listOf("rule-6") }
        assertThat(table(drop.xml).rules.map { it.id }).containsExactly("rule-1", "rule-2", "rule-3", "rule-4", "rule-5")

        val withDefault = reference.replace("<text>&gt; 95</text>", "<text></text>")
        assertThat(DmnMutator.mutants(withDefault, setOf(RULE_DROP)).map { it.region.ruleIds.single() }).contains("rule-6")
    }

    @Test
    fun `OUTPUT_SWAP replaces each output with every other value of its column`() {
        assertThat(of(OUTPUT_SWAP)).hasSize(12)

        val swaps = of(OUTPUT_SWAP).filter { it.region.ruleIds == listOf("rule-3") }
        assertThat(swaps.map { it.region.after }).containsExactlyInAnyOrder("\"Fixed30\"", "\"ManualReview\"")
        assertThat(swaps.map { it.region.input }).containsOnly("product")
    }

    @Test
    fun `CONDITION_DROP empties each non-empty input entry`() {
        assertThat(of(CONDITION_DROP)).hasSize(9)

        val drop = only(CONDITION_DROP, "rule-3", "\"stable\"", "")
        assertThat(drop.region.input).isEqualTo("application.incomeStability")
        assertThat(entry(drop.xml, "rule-3", 3)).isEmpty()
    }

    @Test
    fun `HIT_POLICY_CHANGE switches to every other evaluable policy`() {
        assertThat(of(HIT_POLICY_CHANGE).map { it.region.after }).containsExactlyInAnyOrder("UNIQUE", "ANY", "COLLECT", "RULE ORDER")
        assertThat(of(HIT_POLICY_CHANGE)).allSatisfy { assertThat(it.region.ruleIds).isEmpty() }
    }

    @Test
    fun `a mutant is killed when the suite fails it under strict validation`() {
        val gt = ruleSet.groundTruth

        assertThat(MutationAnalysis.isKilled(only(THRESHOLD_SHIFT, "rule-6", "> 95", "> 96"), gt, strict)).isTrue()
        assertThat(MutationAnalysis.isKilled(of(RULE_DROP).single { it.region.ruleIds == listOf("rule-4") }, gt, strict)).isTrue()
        assertThat(MutationAnalysis.isKilled(only(THRESHOLD_SHIFT, "rule-6", "> 95", "> 94"), gt, strict)).isFalse()
        assertThat(MutationAnalysis.isKilled(only(BOUNDARY_FLIP, "rule-5", "> 50", ">= 50"), gt, strict)).isFalse()
    }
}
