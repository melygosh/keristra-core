package com.grimels.keristra.core.orchestration

import com.grimels.keristra.core.generation.Tracks
import com.grimels.keristra.core.generation.dmn.GeneratedDmn
import com.grimels.keristra.core.generation.tests.TestSuite
import com.grimels.keristra.core.generation.tests.TestSuiteParseException
import com.grimels.keristra.core.llm.LlmResponse
import com.grimels.keristra.core.validation.CaseOutcome
import com.grimels.keristra.core.validation.CaseResult
import com.grimels.keristra.core.validation.SemanticHash
import com.grimels.keristra.core.validation.ValidationReport
import com.grimels.keristra.core.validation.Validator

enum class RepairStrategy { STATIC_ORACLE, PAIRED_REGEN, ESCALATE_ON_STABLE_DISAGREEMENT }

enum class CycleOutcome { ACCEPTED, REJECTED_BUDGET, AMBIGUOUS, ERROR }

data class DispatcherConfig(
    val strategy: RepairStrategy,
    val retryBudget: Int = 3,
    val stableAttempts: Int = 2,
    val conflictMemory: Boolean = false,
)

data class Conflict(
    val key: String,
    val case: Map<String, Any?>,
    val outcome: CaseOutcome,
    val actual: List<Map<String, Any?>>,
    val semanticHash: String?,
    val firstSeenAttempt: Int,
    val readjudicated: Boolean = false,
)

enum class ConflictCloseReason { DMN_FIXED, ORACLE_RETRACTION }

data class ClosedConflict(val conflict: Conflict, val reason: ConflictCloseReason, val newExpected: Any? = null)

data class Adjudication(
    val conflictKey: String,
    val previousExpected: Any?,
    val newExpected: Any?,
    val agreesWithDmn: Boolean,
    val promptId: String?,
    val promptHash: String?,
    val response: LlmResponse?,
    val error: String? = null,
)

data class Attempt(
    val number: Int,
    val testsRegenerated: Boolean,
    val tests: TestSuite?,
    val dmn: GeneratedDmn?,
    val report: ValidationReport?,
    val semanticHash: String?,
    val error: String? = null,
    /** Added to each track's configured seed for this attempt: `cycle * 1000 + attempt`. */
    val seedOffset: Long = 0,
    /** Raw answer of a test track whose output did not parse, kept so the analysis needs no new calls. */
    val failedTestsResponse: LlmResponse? = null,
    val accepted: Boolean = false,
    val openConflicts: List<Conflict> = emptyList(),
    val closedConflicts: List<ClosedConflict> = emptyList(),
    val adjudications: List<Adjudication> = emptyList(),
)

data class CycleResult(
    val outcome: CycleOutcome,
    val attempts: List<Attempt>,
    val conflictingCases: List<CaseResult> = emptyList(),
    val message: String? = null,
    val openConflicts: List<Conflict> = emptyList(),
) {
    val finalDmn: GeneratedDmn? get() = attempts.lastOrNull { it.dmn != null }?.dmn
}

/**
 * Runs one generate-validate cycle (REVISION_PLAN 4.5). Per attempt the test track runs first, then the DMN track
 * (V0 order), then the validator. Strategies:
 * - STATIC_ORACLE (A): the first test suite that parses is the oracle for the whole cycle; only the DMN is regenerated.
 * - PAIRED_REGEN (B): both tracks are regenerated on every attempt (V0 behaviour).
 * - ESCALATE_ON_STABLE_DISAGREEMENT (V3): as B, but stops with AMBIGUOUS once the DMN's [SemanticHash] has been the
 *   same for [DispatcherConfig.stableAttempts] consecutive attempts and those attempts share a failure in the same
 *   region. Both tracks are resampled because only independent oracles that keep disagreeing with the same logic in
 *   the same way point at ambiguous rule text; a fixed oracle would only measure the DMN generator's stubbornness.
 *
 * Region of a failing case = its disagreement signature: outcome + expected + actual.
 *
 * With [DispatcherConfig.conflictMemory] (B and V3 only), a cycle keeps every disagreement it has seen until it is
 * resolved, so an oracle that simply stops asking about a conflicting input cannot get the table accepted (F17); V3
 * then escalates when the logic is stable and conflicts remain open. See [ConflictMemory].
 * A test suite that does not parse fails the attempt (no DMN is generated, as in V0); any other exception from a
 * track (transport, auth, model not found) ends the cycle with ERROR.
 */
class Dispatcher(private val tracks: Tracks, private val validator: Validator, private val config: DispatcherConfig) {

    init {
        require(config.retryBudget >= 1) { "retryBudget must be at least 1" }
        require(config.stableAttempts >= 1) { "stableAttempts must be at least 1" }
    }

    /** Conflict memory needs a test track that resamples; a static oracle already keeps every expectation. */
    private val memoryActive = config.conflictMemory && config.strategy != RepairStrategy.STATIC_ORACLE

    fun runCycle(rules: String, domain: String, schemaPaths: Set<String>? = null, cycle: Int = 0): CycleResult {
        val attempts = mutableListOf<Attempt>()
        var oracle: TestSuite? = null
        val conflicts = ConflictMemory()

        for (number in 1..config.retryBudget) {
            val seedOffset = cycle * 1000L + number
            val regenerateTests = oracle == null || config.strategy != RepairStrategy.STATIC_ORACLE
            val tests = if (!regenerateTests) {
                oracle!!
            } else {
                try {
                    tracks.tests.generate(rules, domain, seedOffset)
                } catch (e: TestSuiteParseException) {
                    attempts += Attempt(
                        number, true, null, null, null, null, e.message, seedOffset, failedTestsResponse = e.response, openConflicts = conflicts.open(),
                    )
                    continue
                } catch (e: Exception) {
                    attempts += Attempt(number, true, null, null, null, null, describe(e), seedOffset, openConflicts = conflicts.open())
                    return CycleResult(CycleOutcome.ERROR, attempts, message = describe(e), openConflicts = conflicts.open())
                }
            }
            oracle = tests

            val dmn = try {
                tracks.dmn.generate(rules, domain, seedOffset)
            } catch (e: Exception) {
                attempts += Attempt(number, regenerateTests, tests, null, null, null, describe(e), seedOffset, openConflicts = conflicts.open())
                return CycleResult(CycleOutcome.ERROR, attempts, message = describe(e), openConflicts = conflicts.open())
            }

            val report = validator.validate(dmn.xml, tests.cases, schemaPaths)
            val hash = SemanticHash.of(dmn.xml)

            if (!memoryActive) {
                attempts += Attempt(number, regenerateTests, tests, dmn, report, hash, seedOffset = seedOffset, accepted = report.accepted)
                if (report.accepted) return CycleResult(CycleOutcome.ACCEPTED, attempts)
                if (config.strategy == RepairStrategy.ESCALATE_ON_STABLE_DISAGREEMENT) {
                    stableDisagreement(attempts)?.let { return CycleResult(CycleOutcome.AMBIGUOUS, attempts, conflictingCases = it) }
                }
                continue
            }

            val step = try {
                conflicts.update(number, dmn.xml, hash, report, tests, rules, domain, seedOffset)
            } catch (e: Exception) {
                attempts += Attempt(number, regenerateTests, tests, dmn, report, hash, describe(e), seedOffset, openConflicts = conflicts.open())
                return CycleResult(CycleOutcome.ERROR, attempts, message = describe(e), openConflicts = conflicts.open())
            }
            val accepted = report.accepted && conflicts.open().isEmpty()
            attempts += Attempt(
                number, regenerateTests, tests, dmn, report, hash, seedOffset = seedOffset, accepted = accepted,
                openConflicts = conflicts.open(), closedConflicts = step.closed, adjudications = step.adjudications,
            )
            if (accepted) return CycleResult(CycleOutcome.ACCEPTED, attempts)
            if (config.strategy == RepairStrategy.ESCALATE_ON_STABLE_DISAGREEMENT && stableWithOpenConflicts(attempts)) {
                return CycleResult(CycleOutcome.AMBIGUOUS, attempts, openConflicts = conflicts.open())
            }
        }
        return CycleResult(CycleOutcome.REJECTED_BUDGET, attempts, openConflicts = conflicts.open())
    }

    /** V3 with memory: the logic has not changed for `stableAttempts` attempts and conflicts are still open. */
    private fun stableWithOpenConflicts(attempts: List<Attempt>): Boolean {
        val window = attempts.takeLast(config.stableAttempts)
        return window.size == config.stableAttempts &&
            window.all { it.semanticHash != null } && window.map { it.semanticHash }.distinct().size == 1 &&
            window.last().openConflicts.isNotEmpty()
    }

    private class Step(val closed: List<ClosedConflict>, val adjudications: List<Adjudication>)

    /**
     * Open conflicts of one cycle (REVISION_PLAN step 10). Every failing case of any attempt is kept and re-run on each
     * later DMN. It closes when a changed DMN passes it (DMN_FIXED), or when the test track, asked about that input
     * alone and without the DMN, now expects what the DMN returns (ORACLE_RETRACTION). Re-adjudication is asked only
     * when an attempt would otherwise be accepted, and at most once per conflict.
     */
    private inner class ConflictMemory {
        private val open = linkedMapOf<String, Conflict>()

        fun open(): List<Conflict> = open.values.toList()

        fun update(
            number: Int, dmnXml: String, hash: String?, report: ValidationReport, tests: TestSuite,
            rules: String, domain: String, seedOffset: Long,
        ): Step {
            val closed = mutableListOf<ClosedConflict>()
            val adjudications = mutableListOf<Adjudication>()

            val previous = open.values.toList()
            if (previous.isNotEmpty()) {
                val rerun = validator.validate(dmnXml, previous.map { it.case })
                previous.forEachIndexed { i, conflict ->
                    val result = rerun.cases.getOrNull(i) ?: return@forEachIndexed
                    if (result.outcome == CaseOutcome.PASS && hash != conflict.semanticHash) {
                        open.remove(conflict.key)
                        closed += ClosedConflict(conflict, ConflictCloseReason.DMN_FIXED)
                    } else if (result.outcome != CaseOutcome.PASS) {
                        open[conflict.key] = conflict.copy(outcome = result.outcome, actual = result.actual, semanticHash = hash)
                    }
                }
            }

            if (report.accepted && tracks.tests.adjudicationPrompt != null) {
                for (conflict in open.values.filter { !it.readjudicated }) {
                    @Suppress("UNCHECKED_CAST")
                    val input = conflict.case["object"] as? Map<String, Any?> ?: emptyMap()
                    val adjudication = try {
                        val verdict = tracks.tests.adjudicate(rules, domain, input, seedOffset)
                        val agrees = validator.validate(dmnXml, listOf(mapOf("object" to input, "expected" to verdict.expected))).accepted
                        Adjudication(conflict.key, conflict.case["expected"], verdict.expected, agrees, verdict.promptId, verdict.promptHash, verdict.response)
                    } catch (e: TestSuiteParseException) {
                        Adjudication(conflict.key, conflict.case["expected"], null, false, e.promptId, null, e.response, e.message)
                    }
                    adjudications += adjudication
                    if (adjudication.agreesWithDmn) {
                        open.remove(conflict.key)
                        closed += ClosedConflict(conflict, ConflictCloseReason.ORACLE_RETRACTION, adjudication.newExpected)
                    } else {
                        open[conflict.key] = conflict.copy(readjudicated = true)
                    }
                }
            }

            for (result in report.cases.filter { it.outcome != CaseOutcome.PASS }) {
                val case = tests.cases.getOrNull(result.index) ?: continue
                val key = conflictKey(case)
                if (key !in open) open[key] = Conflict(key, case, result.outcome, result.actual, hash, number)
            }
            return Step(closed, adjudications)
        }
    }

    /** Same input and same expectation = same conflict, whatever key order the oracle used. */
    private fun conflictKey(case: Map<String, Any?>) = "${canonical(case["object"])} => ${canonical(case["expected"])}"

    private fun canonical(value: Any?): String = when (value) {
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { "${it.key}:${canonical(it.value)}" }
        is List<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }

    /** The latest attempt's failing cases in regions shared by the whole stability window, or null if not stable. */
    private fun stableDisagreement(attempts: List<Attempt>): List<CaseResult>? {
        val window = attempts.takeLast(config.stableAttempts)
        if (window.size < config.stableAttempts || window.any { it.semanticHash == null || it.report == null }) return null
        if (window.map { it.semanticHash }.distinct().size != 1) return null

        val failuresByRegion = window.map(::failuresByRegion)
        val sharedRegions = failuresByRegion.map { it.keys }.reduce { shared, regions -> shared intersect regions }
        if (sharedRegions.isEmpty()) return null
        return failuresByRegion.last().filterKeys { it in sharedRegions }.values.flatten()
    }

    private fun failuresByRegion(attempt: Attempt): Map<String, List<CaseResult>> =
        attempt.report!!.cases
            .filter { it.outcome != CaseOutcome.PASS }
            .groupBy { "${it.outcome} | expected ${attempt.tests!!.cases.getOrNull(it.index)?.get("expected")} | actual ${it.actual}" }

    private fun describe(e: Exception) = "${e.javaClass.simpleName}: ${e.message}"
}
