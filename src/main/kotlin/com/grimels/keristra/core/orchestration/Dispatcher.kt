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

data class DispatcherConfig(val strategy: RepairStrategy, val retryBudget: Int = 3, val stableAttempts: Int = 2)

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
)

data class CycleResult(
    val outcome: CycleOutcome,
    val attempts: List<Attempt>,
    val conflictingCases: List<CaseResult> = emptyList(),
    val message: String? = null,
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
 * A test suite that does not parse fails the attempt (no DMN is generated, as in V0); any other exception from a
 * track (transport, auth, model not found) ends the cycle with ERROR.
 */
class Dispatcher(private val tracks: Tracks, private val validator: Validator, private val config: DispatcherConfig) {

    init {
        require(config.retryBudget >= 1) { "retryBudget must be at least 1" }
        require(config.stableAttempts >= 1) { "stableAttempts must be at least 1" }
    }

    fun runCycle(rules: String, domain: String, schemaPaths: Set<String>? = null, cycle: Int = 0): CycleResult {
        val attempts = mutableListOf<Attempt>()
        var oracle: TestSuite? = null

        for (number in 1..config.retryBudget) {
            val seedOffset = cycle * 1000L + number
            val regenerateTests = oracle == null || config.strategy != RepairStrategy.STATIC_ORACLE
            val tests = if (!regenerateTests) {
                oracle!!
            } else {
                try {
                    tracks.tests.generate(rules, domain, seedOffset)
                } catch (e: TestSuiteParseException) {
                    attempts += Attempt(number, true, null, null, null, null, e.message, seedOffset, failedTestsResponse = e.response)
                    continue
                } catch (e: Exception) {
                    attempts += Attempt(number, true, null, null, null, null, describe(e), seedOffset)
                    return CycleResult(CycleOutcome.ERROR, attempts, message = describe(e))
                }
            }
            oracle = tests

            val dmn = try {
                tracks.dmn.generate(rules, domain, seedOffset)
            } catch (e: Exception) {
                attempts += Attempt(number, regenerateTests, tests, null, null, null, describe(e), seedOffset)
                return CycleResult(CycleOutcome.ERROR, attempts, message = describe(e))
            }

            val report = validator.validate(dmn.xml, tests.cases, schemaPaths)
            attempts += Attempt(number, regenerateTests, tests, dmn, report, SemanticHash.of(dmn.xml), seedOffset = seedOffset)

            if (report.accepted) return CycleResult(CycleOutcome.ACCEPTED, attempts)
            if (config.strategy == RepairStrategy.ESCALATE_ON_STABLE_DISAGREEMENT) {
                stableDisagreement(attempts)?.let { return CycleResult(CycleOutcome.AMBIGUOUS, attempts, conflictingCases = it) }
            }
        }
        return CycleResult(CycleOutcome.REJECTED_BUDGET, attempts)
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
