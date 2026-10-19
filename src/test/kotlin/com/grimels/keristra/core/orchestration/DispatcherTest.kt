package com.grimels.keristra.core.orchestration

import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.ScriptedLlmClient
import com.grimels.keristra.core.generation.Tracks
import com.grimels.keristra.core.generation.dmn.DmnGenerator
import com.grimels.keristra.core.generation.fixedSchema
import com.grimels.keristra.core.generation.tests.TestSuiteGenerator
import com.grimels.keristra.core.orchestration.CycleOutcome.ACCEPTED
import com.grimels.keristra.core.orchestration.CycleOutcome.AMBIGUOUS
import com.grimels.keristra.core.orchestration.CycleOutcome.ERROR
import com.grimels.keristra.core.orchestration.CycleOutcome.REJECTED_BUDGET
import com.grimels.keristra.core.orchestration.RepairStrategy.ESCALATE_ON_STABLE_DISAGREEMENT
import com.grimels.keristra.core.orchestration.RepairStrategy.PAIRED_REGEN
import com.grimels.keristra.core.orchestration.RepairStrategy.STATIC_ORACLE
import com.grimels.keristra.core.validation.CaseOutcome
import com.grimels.keristra.core.validation.In
import com.grimels.keristra.core.validation.Out
import com.grimels.keristra.core.validation.SemanticHash
import com.grimels.keristra.core.validation.StrictValidator
import com.grimels.keristra.core.validation.app
import com.grimels.keristra.core.validation.dmn
import com.grimels.keristra.core.validation.firstNoDefault
import com.grimels.keristra.core.validation.testEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DispatcherTest {

    /** Disagrees with [suite]: no default rule, so (false, 720) gives ARM7/1 where the oracle expects Fixed30. */
    private val badDmn = firstNoDefault
    private val badDmnRelabelled = firstNoDefault.replace("rule-", "r-").replace("\n", "\n  ")
    private val otherBadDmn = firstNoDefault.replace("&gt;= 700", "&gt;= 710")
    private val goodDmn = dmn(
        "FIRST",
        listOf(In("application.fixedPreference", "boolean"), In("application.creditScore", "double")),
        Out("product"),
        listOf(listOf("true", "") to "\"Fixed30\"", listOf("", "") to "\"Fixed30\""),
    )

    private val suite = """[{"object": ${app(true, 650)}, "expected": [{"product": "Fixed30"}]},
                            {"object": ${app(false, 720)}, "expected": [{"product": "Fixed30"}]}]"""
    /** Fails [badDmn] in a different region: EMPTY_RESULT where ARM7/1 is expected. */
    private val otherRegionSuite = """[{"object": ${app(false, 650)}, "expected": [{"product": "ARM7/1"}]}]"""

    private class Run(val dmnClient: ScriptedLlmClient, val testsClient: ScriptedLlmClient, val result: CycleResult)

    private fun run(
        config: DispatcherConfig,
        dmnReplies: List<Any>,
        testReplies: List<Any>,
        testSeed: Long? = null,
        cycle: Int = 0,
    ): Run {
        val dmnClient = ScriptedLlmClient(*dmnReplies.toTypedArray())
        val testsClient = ScriptedLlmClient(*testReplies.toTypedArray())
        val tracks = Tracks(
            dmn = DmnGenerator(dmnClient, PromptTemplate.load("dmn", "v1"), fixedSchema),
            tests = TestSuiteGenerator(
                testsClient, PromptTemplate.load("tests", "v1"), fixedSchema, GenerationSettings(seed = testSeed),
                PromptTemplate.load("tests-adjudicate", "v1"),
            ),
        )
        val result = Dispatcher(tracks, StrictValidator(testEngine), config).runCycle(rules = "rules", domain = "mortgage", cycle = cycle)
        return Run(dmnClient, testsClient, result)
    }

    @Test
    fun `seeds differ per attempt and per cycle, so a seeded model does not repeat itself`() {
        val cycle2 = run(DispatcherConfig(PAIRED_REGEN), listOf(badDmn, goodDmn), listOf(suite, suite), testSeed = 100, cycle = 2)
        val cycle3 = run(DispatcherConfig(PAIRED_REGEN), listOf(goodDmn), listOf(suite), testSeed = 100, cycle = 3)

        assertThat(cycle2.testsClient.requests.map { it.seed }).containsExactly(2101L, 2102L)
        assertThat(cycle2.result.attempts.map { it.seedOffset }).containsExactly(2001L, 2002L)
        assertThat(cycle3.testsClient.requests.map { it.seed }).containsExactly(3101L)
    }

    // --- unparseable oracle output (EXPERIMENTS.md section 9: an infrastructure failure, not an attempt) ---

    @Test
    fun `an oracle that does not parse is retried within the same attempt with a new seed, keeping its raw output`() {
        for (strategy in listOf(STATIC_ORACLE, PAIRED_REGEN)) {
            val run = run(DispatcherConfig(strategy), dmnReplies = listOf(goodDmn), testReplies = listOf("not json", suite), testSeed = 100)

            assertThat(run.result.outcome).isEqualTo(ACCEPTED)
            assertThat(run.result.attempts).hasSize(1)
            val failure = run.result.attempts.single().testParseFailures.single()
            assertThat(failure.response.text).isEqualTo("not json")
            assertThat(failure.error).contains("No JSON array")
            assertThat(run.testsClient.requests.map { it.seed }).containsExactly(101L, 201L)
            assertThat(run.dmnClient.requests).hasSize(1)
        }
    }

    @Test
    fun `no parseable suite within 2 x budget test-track calls ends the cycle with ERROR`() {
        val run = run(DispatcherConfig(PAIRED_REGEN, retryBudget = 2), dmnReplies = emptyList(), testReplies = listOf("a", "b", "c", "d"))

        assertThat(run.result.outcome).isEqualTo(ERROR)
        assertThat(run.result.message).contains("No parseable test suite")
        assertThat(run.result.attempts.single().testParseFailures).hasSize(4)
        assertThat(run.dmnClient.requests).isEmpty()
    }

    @Test
    fun `parse failures across attempts count towards the same cap`() {
        val run = run(
            DispatcherConfig(PAIRED_REGEN, retryBudget = 2),
            dmnReplies = listOf(badDmn),
            testReplies = listOf("bad", suite, "bad", "bad"),
        )

        assertThat(run.result.outcome).isEqualTo(ERROR)
        assertThat(run.result.attempts.map { it.testParseFailures.size }).containsExactly(1, 2)
        assertThat(run.testsClient.requests).hasSize(4)
    }

    @Test
    fun `defaults - retry budget 3, stability window 2`() {
        val config = DispatcherConfig(PAIRED_REGEN)

        assertThat(config.retryBudget).isEqualTo(3)
        assertThat(config.stableAttempts).isEqualTo(2)
    }

    // --- A: STATIC_ORACLE ---

    @Test
    fun `A accepts on the first attempt`() {
        val run = run(DispatcherConfig(STATIC_ORACLE), dmnReplies = listOf(goodDmn), testReplies = listOf(suite))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        assertThat(run.result.attempts).hasSize(1)
        assertThat(run.result.finalDmn!!.xml).isEqualTo(goodDmn)
    }

    @Test
    fun `A regenerates only the DMN and keeps the oracle`() {
        val run = run(DispatcherConfig(STATIC_ORACLE), dmnReplies = listOf(badDmn, goodDmn), testReplies = listOf(suite))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        assertThat(run.result.attempts.map { it.testsRegenerated }).containsExactly(true, false)
        assertThat(run.result.attempts[1].tests).isSameAs(run.result.attempts[0].tests)
        assertThat(run.testsClient.requests).hasSize(1)
        assertThat(run.dmnClient.requests).hasSize(2)
    }

    @Test
    fun `A stops at the retry budget`() {
        val run = run(DispatcherConfig(STATIC_ORACLE), dmnReplies = listOf(badDmn, badDmn, badDmn), testReplies = listOf(suite))

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        assertThat(run.result.attempts).hasSize(3)
        assertThat(run.testsClient.requests).hasSize(1)
        assertThat(run.result.finalDmn!!.xml).isEqualTo(badDmn)
    }

    // --- B: PAIRED_REGEN ---

    @Test
    fun `B regenerates both tracks on every attempt`() {
        val run = run(DispatcherConfig(PAIRED_REGEN), dmnReplies = listOf(badDmn, goodDmn), testReplies = listOf(suite, suite))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        assertThat(run.result.attempts.map { it.testsRegenerated }).containsExactly(true, true)
        assertThat(run.testsClient.requests).hasSize(2)
        assertThat(run.dmnClient.requests).hasSize(2)
    }

    @Test
    fun `B honours a configured budget`() {
        val run = run(DispatcherConfig(PAIRED_REGEN, retryBudget = 2), dmnReplies = listOf(badDmn, badDmn), testReplies = listOf(suite, suite))

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        assertThat(run.result.attempts).hasSize(2)
    }

    @Test
    fun `every attempt records its report and the semantic hash of its DMN`() {
        val run = run(DispatcherConfig(PAIRED_REGEN), dmnReplies = listOf(badDmn, goodDmn), testReplies = listOf(suite, suite))

        val first = run.result.attempts[0]
        assertThat(first.number).isEqualTo(1)
        assertThat(first.report!!.accepted).isFalse()
        assertThat(first.semanticHash).isEqualTo(SemanticHash.of(badDmn))
        assertThat(run.result.attempts[1].report!!.accepted).isTrue()
    }

    // --- V3: ESCALATE_ON_STABLE_DISAGREEMENT ---

    @Test
    fun `V3 returns AMBIGUOUS when the logic is stable and fresh oracles disagree the same way`() {
        val run = run(
            DispatcherConfig(ESCALATE_ON_STABLE_DISAGREEMENT),
            dmnReplies = listOf(badDmn, badDmnRelabelled, goodDmn),
            testReplies = listOf(suite, suite, suite),
        )

        assertThat(run.result.outcome).isEqualTo(AMBIGUOUS)
        assertThat(run.result.attempts).hasSize(2)
        assertThat(run.result.attempts.map { it.semanticHash }.distinct()).hasSize(1)
        assertThat(run.result.conflictingCases).isNotEmpty()
        assertThat(run.result.conflictingCases).allSatisfy { assertThat(it.outcome).isEqualTo(CaseOutcome.MISMATCH) }
        assertThat(run.result.conflictingCases.flatMap { it.actual }).contains(mapOf("product" to "ARM7/1"))
        assertThat(run.dmnClient.requests).hasSize(2)
    }

    @Test
    fun `V3 keeps going while the DMN logic changes`() {
        val run = run(
            DispatcherConfig(ESCALATE_ON_STABLE_DISAGREEMENT),
            dmnReplies = listOf(badDmn, otherBadDmn, badDmn),
            testReplies = listOf(suite, suite, suite),
        )

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        assertThat(run.result.attempts).hasSize(3)
    }

    @Test
    fun `V3 keeps going when the failures move to another region`() {
        val run = run(
            DispatcherConfig(ESCALATE_ON_STABLE_DISAGREEMENT),
            dmnReplies = listOf(badDmn, badDmn, badDmn),
            testReplies = listOf(suite, otherRegionSuite, suite),
        )

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        assertThat(run.result.attempts).hasSize(3)
    }

    @Test
    fun `V3 still accepts a passing table`() {
        val run = run(DispatcherConfig(ESCALATE_ON_STABLE_DISAGREEMENT), dmnReplies = listOf(goodDmn), testReplies = listOf(suite))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
    }

    // --- errors ---

    @Test
    fun `an LLM failure ends the cycle with ERROR`() {
        val run = run(
            DispatcherConfig(PAIRED_REGEN),
            dmnReplies = listOf(RuntimeException("connection refused")),
            testReplies = listOf(suite, suite),
        )

        assertThat(run.result.outcome).isEqualTo(ERROR)
        assertThat(run.result.message).contains("connection refused")
        assertThat(run.result.attempts).hasSize(1)
    }

    // --- conflict memory (REVISION_PLAN step 10, finding F17) ---

    /** Passes [badDmn]: the second oracle simply leaves out the conflicting input (false, 720). */
    private val suiteWithoutConflict = """[{"object": ${app(true, 650)}, "expected": [{"product": "Fixed30"}]}]"""
    private fun verdict(product: String) = """[{"object": ${app(false, 720)}, "expected": [{"product": "$product"}]}]"""

    private fun memory(strategy: RepairStrategy, retryBudget: Int = 2) = DispatcherConfig(strategy, retryBudget, conflictMemory = true)

    @Test
    fun `F17 - without conflict memory an oracle that drops the conflicting case lets the unchanged DMN through`() {
        val run = run(DispatcherConfig(PAIRED_REGEN), listOf(badDmn, badDmn), listOf(suite, suiteWithoutConflict))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        assertThat(run.result.attempts.map { it.semanticHash }.distinct()).hasSize(1)
    }

    @Test
    fun `with conflict memory the dropped conflict keeps the cycle open`() {
        val run = run(memory(PAIRED_REGEN), listOf(badDmn, badDmn), listOf(suite, suiteWithoutConflict, verdict("Fixed30")))

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        val second = run.result.attempts[1]
        assertThat(second.report!!.accepted).describedAs("the fresh suite alone passes").isTrue()
        assertThat(second.accepted).isFalse()
        val conflict = run.result.openConflicts.single()
        assertThat(conflict.case["expected"]).isEqualTo(listOf(mapOf("product" to "Fixed30")))
        assertThat(conflict.actual).containsExactly(mapOf("product" to "ARM7/1"))
        assertThat(conflict.firstSeenAttempt).isEqualTo(1)
        val adjudication = second.adjudications.single()
        assertThat(adjudication.newExpected).isEqualTo(listOf(mapOf("product" to "Fixed30")))
        assertThat(adjudication.agreesWithDmn).isFalse()
    }

    @Test
    fun `a changed DMN that passes the conflict closes it - reason DMN_FIXED`() {
        val run = run(memory(PAIRED_REGEN), listOf(badDmn, goodDmn), listOf(suite, suiteWithoutConflict))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        assertThat(run.result.attempts[1].closedConflicts.single().reason).isEqualTo(ConflictCloseReason.DMN_FIXED)
        assertThat(run.result.openConflicts).isEmpty()
        assertThat(run.testsClient.requests).hasSize(2)
    }

    @Test
    fun `an oracle retraction that agrees with the DMN closes the conflict - reason ORACLE_RETRACTION`() {
        val run = run(memory(PAIRED_REGEN), listOf(badDmn, badDmn), listOf(suite, suiteWithoutConflict, verdict("ARM7/1")))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        val closed = run.result.attempts[1].closedConflicts.single()
        assertThat(closed.reason).isEqualTo(ConflictCloseReason.ORACLE_RETRACTION)
        assertThat(closed.newExpected).isEqualTo(listOf(mapOf("product" to "ARM7/1")))
        assertThat(run.testsClient.requests[2].user).contains("\"creditScore\":720").doesNotContain("<definitions")
    }

    @Test
    fun `a changed verdict that still disagrees with the DMN does not close the conflict`() {
        val run = run(memory(PAIRED_REGEN), listOf(badDmn, badDmn), listOf(suite, suiteWithoutConflict, verdict("ManualReview")))

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        assertThat(run.result.attempts[1].adjudications.single().agreesWithDmn).isFalse()
        assertThat(run.result.openConflicts).hasSize(1)
    }

    @Test
    fun `each conflict is re-adjudicated at most once per cycle`() {
        val run = run(
            memory(PAIRED_REGEN, retryBudget = 3),
            listOf(badDmn, badDmn, badDmn),
            listOf(suite, suiteWithoutConflict, verdict("Fixed30"), suiteWithoutConflict),
        )

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        assertThat(run.result.attempts.map { it.adjudications.size }).containsExactly(0, 1, 0)
        assertThat(run.testsClient.requests).hasSize(4)
    }

    @Test
    fun `the same failing case in later attempts is one conflict`() {
        val run = run(memory(PAIRED_REGEN), listOf(badDmn, badDmn), listOf(suite, suite))

        assertThat(run.result.attempts.map { it.openConflicts.size }).containsExactly(1, 1)
        assertThat(run.result.openConflicts.single().firstSeenAttempt).isEqualTo(1)
    }

    @Test
    fun `V3 with memory returns AMBIGUOUS when the logic is stable and a conflict stays open`() {
        val run = run(
            memory(ESCALATE_ON_STABLE_DISAGREEMENT, retryBudget = 3),
            listOf(badDmn, badDmnRelabelled),
            listOf(suite, suiteWithoutConflict, verdict("Fixed30")),
        )

        assertThat(run.result.outcome).isEqualTo(AMBIGUOUS)
        assertThat(run.result.attempts).hasSize(2)
        assertThat(run.result.openConflicts).hasSize(1)
    }

    @Test
    fun `STATIC_ORACLE ignores conflict memory - its oracle never changes`() {
        val run = run(memory(STATIC_ORACLE), listOf(badDmn, goodDmn), listOf(suite))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        assertThat(run.result.attempts.flatMap { it.openConflicts + it.adjudications }).isEmpty()
    }

    @Test
    fun `an unparseable verdict leaves the conflict open and is recorded`() {
        val run = run(memory(PAIRED_REGEN), listOf(badDmn, badDmn), listOf(suite, suiteWithoutConflict, "no idea"))

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        val adjudication = run.result.attempts[1].adjudications.single()
        assertThat(adjudication.error).isNotBlank()
        assertThat(adjudication.response!!.text).isEqualTo("no idea")
    }

    // --- ambiguity flags (arm A1, REVISION_PLAN step 16) ---

    private val note = "rule 1 (Fixed30) and rule 2 (ARM7/1) both apply; the rules do not state which takes precedence"
    /** [suite] with its second case flagged; the flagged expectation agrees with [goodDmn] and disagrees with [badDmn]. */
    private val flaggedSuite = """[{"object": ${app(true, 650)}, "expected": [{"product": "Fixed30"}]},
                                   {"object": ${app(false, 720)}, "expected": [{"product": "Fixed30"}], "ambiguous": true, "ambiguityNote": "$note"}]"""

    private fun a1(retryBudget: Int = 2, flags: Boolean = true) =
        DispatcherConfig(ESCALATE_ON_STABLE_DISAGREEMENT, retryBudget, conflictMemory = true, escalateOnAmbiguityFlags = flags)

    @Test
    fun `A1 - a flagged case that agrees with the DMN turns the accepted attempt into AMBIGUOUS from FLAGS`() {
        val run = run(a1(), listOf(goodDmn), listOf(flaggedSuite))

        assertThat(run.result.outcome).isEqualTo(AMBIGUOUS)
        assertThat(run.result.ambiguitySource).isEqualTo(AmbiguitySource.FLAGS)
        assertThat(run.result.attempts).hasSize(1)
        assertThat(run.result.attempts.single().report!!.accepted).describedAs("flagged cases are validated as usual").isTrue()
        val flagged = run.result.attempts.single().flaggedCases.single()
        assertThat(flagged.source).isEqualTo(FlagSource.SUITE)
        assertThat(flagged.index).isEqualTo(1)
        assertThat(flagged.input).isEqualTo(mapOf("application" to mapOf("fixedPreference" to false, "creditScore" to 720.0)))
        assertThat(flagged.expected).isEqualTo(listOf(mapOf("product" to "Fixed30")))
        assertThat(flagged.note).isEqualTo(note)
        assertThat(run.result.flaggedCases).containsExactly(flagged)
    }

    @Test
    fun `A1 - an unflagged suite is ACCEPTED`() {
        val run = run(a1(), listOf(goodDmn), listOf(suite))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        assertThat(run.result.ambiguitySource).isNull()
        assertThat(run.result.attempts.single().flaggedCases).isEmpty()
    }

    @Test
    fun `without escalateOnAmbiguityFlags flagged cases are only logged`() {
        for (config in listOf(a1(flags = false), DispatcherConfig(PAIRED_REGEN))) {
            val run = run(config, listOf(goodDmn), listOf(flaggedSuite))

            assertThat(run.result.outcome).isEqualTo(ACCEPTED)
            assertThat(run.result.attempts.single().flaggedCases).hasSize(1)
        }
    }

    @Test
    fun `a flagged case that fails the DMN is a failure like any other`() {
        val run = run(a1(retryBudget = 1), listOf(badDmn), listOf(flaggedSuite))

        assertThat(run.result.outcome).isEqualTo(REJECTED_BUDGET)
        assertThat(run.result.attempts.single().report!!.cases[1].outcome).isEqualTo(CaseOutcome.MISMATCH)
        assertThat(run.result.ambiguitySource).isNull()
    }

    @Test
    fun `escalation on flags applies without conflict memory too`() {
        val run = run(DispatcherConfig(PAIRED_REGEN, escalateOnAmbiguityFlags = true), listOf(goodDmn), listOf(flaggedSuite))

        assertThat(run.result.outcome).isEqualTo(AMBIGUOUS)
        assertThat(run.result.ambiguitySource).isEqualTo(AmbiguitySource.FLAGS)
    }

    @Test
    fun `a flagged re-adjudication verdict counts as a flag of the attempt`() {
        val flaggedVerdict = """[{"object": ${app(false, 720)}, "expected": [{"product": "ARM7/1"}], "ambiguous": true, "ambiguityNote": "$note"}]"""

        val run = run(a1(), listOf(badDmn, badDmn), listOf(suite, suiteWithoutConflict, flaggedVerdict))

        assertThat(run.result.outcome).isEqualTo(AMBIGUOUS)
        assertThat(run.result.ambiguitySource).isEqualTo(AmbiguitySource.FLAGS)
        val second = run.result.attempts[1]
        assertThat(second.adjudications.single().ambiguous).isTrue()
        assertThat(second.adjudications.single().ambiguityNote).isEqualTo(note)
        val flagged = second.flaggedCases.single()
        assertThat(flagged.source).isEqualTo(FlagSource.ADJUDICATION)
        assertThat(flagged.index).isNull()
        assertThat(flagged.expected).isEqualTo(listOf(mapOf("product" to "ARM7/1")))
    }

    @Test
    fun `stable disagreement reports its source as DISAGREEMENT`() {
        val run = run(a1(retryBudget = 3), listOf(badDmn, badDmnRelabelled), listOf(suite, suiteWithoutConflict, verdict("Fixed30")))

        assertThat(run.result.outcome).isEqualTo(AMBIGUOUS)
        assertThat(run.result.ambiguitySource).isEqualTo(AmbiguitySource.DISAGREEMENT)
    }

    @Test
    fun `ambiguous given as the string true counts, false does not`() {
        val stringFlag = flaggedSuite.replace("\"ambiguous\": true", "\"ambiguous\": \"true\"")
        val falseFlag = flaggedSuite.replace("\"ambiguous\": true", "\"ambiguous\": false")

        assertThat(run(a1(), listOf(goodDmn), listOf(stringFlag)).result.outcome).isEqualTo(AMBIGUOUS)
        assertThat(run(a1(), listOf(goodDmn), listOf(falseFlag)).result.outcome).isEqualTo(ACCEPTED)
    }
}
