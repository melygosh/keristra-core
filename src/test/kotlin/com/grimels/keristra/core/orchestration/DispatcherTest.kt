package com.grimels.keristra.core.orchestration

import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.Tracks
import com.grimels.keristra.core.generation.dmn.DmnGenerator
import com.grimels.keristra.core.generation.fixedSchema
import com.grimels.keristra.core.generation.tests.TestSuiteGenerator
import com.grimels.keristra.core.llm.LlmClient
import com.grimels.keristra.core.llm.LlmRequest
import com.grimels.keristra.core.llm.LlmResponse
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

/** Answers with the scripted replies in order; a Throwable in the script is thrown instead. */
private class ScriptedLlmClient(vararg replies: Any) : LlmClient {
    override val modelId = "scripted"
    private val script = ArrayDeque(replies.toList())
    val requests = mutableListOf<LlmRequest>()

    override fun complete(request: LlmRequest): LlmResponse {
        requests += request
        val next = script.removeFirstOrNull() ?: throw AssertionError("Unexpected LLM call #${requests.size}")
        if (next is Throwable) throw next
        return LlmResponse(next as String, modelId, inputTokens = 1, outputTokens = 1, latencyMs = 1)
    }
}

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

    private fun run(config: DispatcherConfig, dmnReplies: List<Any>, testReplies: List<Any>): Run {
        val dmnClient = ScriptedLlmClient(*dmnReplies.toTypedArray())
        val testsClient = ScriptedLlmClient(*testReplies.toTypedArray())
        val tracks = Tracks(
            dmn = DmnGenerator(dmnClient, PromptTemplate.load("dmn", "v1"), fixedSchema),
            tests = TestSuiteGenerator(testsClient, PromptTemplate.load("tests", "v1"), fixedSchema),
        )
        val result = Dispatcher(tracks, StrictValidator(testEngine), config).runCycle(rules = "rules", domain = "mortgage")
        return Run(dmnClient, testsClient, result)
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

    @Test
    fun `A regenerates the oracle until one parses, without generating a DMN for the failed attempt`() {
        val run = run(DispatcherConfig(STATIC_ORACLE), dmnReplies = listOf(goodDmn), testReplies = listOf("not json", suite))

        assertThat(run.result.outcome).isEqualTo(ACCEPTED)
        assertThat(run.result.attempts.map { it.testsRegenerated }).containsExactly(true, true)
        assertThat(run.result.attempts[0].error).contains("No JSON array")
        assertThat(run.result.attempts[0].dmn).isNull()
        assertThat(run.dmnClient.requests).hasSize(1)
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
}
