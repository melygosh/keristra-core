package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.grimels.keristra.core.context.DomainRegistry
import com.grimels.keristra.core.generation.Tracks
import com.grimels.keristra.core.generation.TracksProvider
import com.grimels.keristra.core.llm.LlmResponse
import com.grimels.keristra.core.orchestration.Attempt
import com.grimels.keristra.core.orchestration.Conflict
import com.grimels.keristra.core.orchestration.CycleOutcome
import com.grimels.keristra.core.orchestration.CycleResult
import com.grimels.keristra.core.orchestration.Dispatcher
import com.grimels.keristra.core.orchestration.DispatcherConfig
import com.grimels.keristra.core.validation.LenientValidator
import com.grimels.keristra.core.validation.StrictValidator
import com.grimels.keristra.core.validation.ValidatorMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Runs an experiment config cycle by cycle and logs it to `<outputDir>/<runId>.jsonl` (REVISION_PLAN 4.6 and 5):
 * `run_started`, then per cycle its `attempt` events and `cycle_finished`, then `run_finished` with aggregates over
 * every finished cycle in the file. Rerunning the same runId resumes: finished cycles are skipped (cycles that ended
 * in ERROR run again), and the config must match apart from `cycles`. Every event carries the `sessionId` of the process that wrote it; attempts of a
 * cycle that never finished (crash) stay in the file and are superseded when the cycle is rerun.
 *
 * Whatever validator runs the loop, the final DMN of every cycle is checked against the rule set's human ground
 * truth with the strict validator, so false accepts and false rejects can be counted.
 */
@Component
class ExperimentRunner(
    private val tracksProvider: TracksProvider,
    private val ruleSets: RuleSetRepository,
    private val domains: DomainRegistry,
    private val lenient: LenientValidator,
    private val strict: StrictValidator,
    private val pricing: PricingProperties,
    private val gitInfo: GitInfo,
) {

    private val logger = LoggerFactory.getLogger(ExperimentRunner::class.java)
    private val mapper = jacksonObjectMapper()

    fun run(config: RunConfig, outputDir: Path): Path {
        if (config.effectiveMode == RunMode.SUITES) return runSuites(config, outputDir)
        Files.createDirectories(outputDir)
        val logFile = outputDir.resolve("${config.runId}.jsonl")

        val previous = JsonlLog.read(logFile, mapper)
        checkResumable(config, previous, logFile)
        // ERROR is an infrastructure failure (network, timeout, auth), not a result: such cycles run again on resume.
        val finished = previous.filter { it["event"] == "cycle_finished" }
            .associateBy { (it["cycle"] as Number).toInt() }
            .filterValues { it["outcome"] != CycleOutcome.ERROR.name }.keys

        val ruleSet = ruleSets.load(config.ruleSet, config.version, config.language)
        val dmnSpec = requireNotNull(config.dmn)
        val testsSpec = requireNotNull(config.tests)
        val tracks = tracksProvider.create(dmnSpec.toTrackConfig(), testsSpec.toTrackConfig())
        val validator = if (config.validator == ValidatorMode.LENIENT) lenient else strict
        val schemaPaths = if (config.validator == ValidatorMode.STRICT) domains.schemaPaths(ruleSet.domain) else null
        val dispatcher = Dispatcher(tracks, validator, DispatcherConfig(config.strategy, config.retryBudget, config.stableAttempts, config.conflictMemory))
        val session = Session(config, UUID.randomUUID().toString(), ruleSet, tracks, dmnSpec, testsSpec)

        JsonlLog.Writer(logFile, mapper).use { out ->
            out.append(runStarted(session, schemaPaths != null, previous.isNotEmpty(), (1..config.cycles).filter { it in finished }))
            for (cycle in 1..config.cycles) {
                if (cycle in finished) continue
                val started = System.nanoTime()
                val result = dispatcher.runCycle(ruleSet.rules, ruleSet.domain, schemaPaths, cycle)
                result.attempts.forEach { out.append(attemptEvent(session, cycle, it)) }
                out.append(cycleFinished(session, cycle, result, (System.nanoTime() - started) / 1_000_000))
                logger.info("[${config.runId}] cycle $cycle/${config.cycles}: ${result.outcome} after ${result.attempts.size} attempt(s)")
            }
            out.append(runFinished(session, JsonlLog.read(logFile, mapper)))
        }
        return logFile
    }

    private fun runSuites(config: RunConfig, outputDir: Path): Path {
        Files.createDirectories(outputDir)
        val logFile = outputDir.resolve("${config.runId}.jsonl")
        val previous = JsonlLog.read(logFile, mapper)
        checkResumable(config, previous, logFile)
        val validator = if (config.validator == ValidatorMode.LENIENT) lenient else strict
        val testsGenerator = config.tests?.let { tracksProvider.tests(it.toTrackConfig()) }
        return SuiteRun(ruleSets, domains, validator, testsGenerator, gitInfo, mapper, ::trackInfo).run(config, logFile, previous)
    }

    private class Session(
        val config: RunConfig, val id: String, val ruleSet: RuleSet, val tracks: Tracks, val dmn: TrackSpec, val tests: TrackSpec,
    )

    private fun checkResumable(config: RunConfig, previous: List<Map<String, Any?>>, logFile: Path) {
        val logged = previous.firstOrNull { it["event"] == "run_started" }?.get("config") as? Map<*, *> ?: return
        // Read the logged config back through RunConfig, so fields added later compare at their defaults.
        val earlier: Map<*, *> = runCatching {
            mapper.readValue<Map<String, Any?>>(mapper.writeValueAsString(mapper.convertValue(logged, RunConfig::class.java)))
        }.getOrDefault(logged)
        val current: Map<String, Any?> = mapper.readValue(mapper.writeValueAsString(config))
        val differing = (earlier.keys + current.keys).map { it.toString() }.toSortedSet()
            .filter { it != "cycles" && earlier[it] != current[it] }
        check(differing.isEmpty()) {
            "Run '${config.runId}' already exists in $logFile with a different config (differs in: $differing); use a new runId"
        }
    }

    private fun base(session: Session, event: String) =
        linkedMapOf<String, Any?>("event" to event, "runId" to session.config.runId, "sessionId" to session.id)

    private fun runStarted(session: Session, schemaCheck: Boolean, resumed: Boolean, skipped: List<Int>) = base(session, "run_started") + mapOf(
        "startedAt" to Instant.now().toString(),
        "resumed" to resumed,
        "skippedCycles" to skipped,
        "config" to session.config,
        "gitCommit" to gitInfo.commit(),
        "models" to mapOf("dmn" to session.dmn.model, "tests" to session.tests.model),
        "prompts" to mapOf(
            "dmn" to mapOf("id" to session.tracks.dmn.prompt.id, "hash" to session.tracks.dmn.prompt.hash),
            "tests" to mapOf("id" to session.tracks.tests.prompt.id, "hash" to session.tracks.tests.prompt.hash),
        ),
        "ruleSet" to mapOf(
            "id" to session.ruleSet.id, "language" to session.ruleSet.language, "hash" to session.ruleSet.hash,
            "domain" to session.ruleSet.domain, "groundTruthSize" to session.ruleSet.groundTruth.size,
        ),
        "schemaCheck" to schemaCheck,
        "rag" to mapOf("dmn" to session.tracks.dmn.retriever?.description, "tests" to null),
        "runtime" to mapOf("java" to System.getProperty("java.version")),
    )

    private fun attemptEvent(session: Session, cycle: Int, attempt: Attempt): Map<String, Any?> {
        val testsResponse = if (attempt.testsRegenerated) attempt.tests?.response ?: attempt.failedTestsResponse else null
        val tests = session.tracks.tests.prompt
        return base(session, "attempt") + mapOf(
            "cycle" to cycle,
            "attempt" to attempt.number,
            "seedOffset" to attempt.seedOffset,
            "testsRegenerated" to attempt.testsRegenerated,
            "tracks" to mapOf(
                "tests" to testsResponse?.let { trackInfo(session.tests, it, tests.id, tests.hash, attempt.seedOffset) },
                "dmn" to attempt.dmn?.let {
                    trackInfo(session.dmn, it.response, it.promptId, it.promptHash, attempt.seedOffset) + mapOf(
                        "rag" to it.retrieved?.let { segments ->
                            mapOf("count" to segments.size, "segments" to segments.map { s -> mapOf("id" to s.id, "score" to s.score) })
                        },
                    )
                },
            ),
            "semanticHash" to attempt.semanticHash,
            "testSuiteSize" to attempt.tests?.cases?.size,
            "accepted" to attempt.accepted,
            "suiteAccepted" to attempt.report?.accepted,
            "tableError" to attempt.report?.tableError,
            "tableErrorMessage" to attempt.report?.tableErrorMessage,
            "counts" to attempt.report?.counts,
            "outcomes" to attempt.report?.cases,
            "error" to attempt.error,
            "dmnXml" to attempt.dmn?.xml,
            "dmnRaw" to attempt.dmn?.response?.text?.takeIf { it != attempt.dmn.xml },
            "testSuite" to attempt.tests?.cases,
            "testsRaw" to attempt.failedTestsResponse?.text,
            "openConflicts" to attempt.openConflicts.map(::conflictInfo),
            "closedConflicts" to attempt.closedConflicts.map {
                mapOf("reason" to it.reason, "newExpected" to it.newExpected) + conflictInfo(it.conflict)
            },
            "adjudications" to attempt.adjudications.map {
                mapOf(
                    "conflictKey" to it.conflictKey,
                    "previousExpected" to it.previousExpected,
                    "newExpected" to it.newExpected,
                    "agreesWithDmn" to it.agreesWithDmn,
                    "error" to it.error,
                    "raw" to it.response?.text,
                    "track" to it.response?.let { response ->
                        trackInfo(session.tests, response, it.promptId.orEmpty(), it.promptHash.orEmpty(), attempt.seedOffset)
                    },
                )
            },
        )
    }

    private fun conflictInfo(conflict: Conflict) = mapOf(
        "key" to conflict.key,
        "object" to conflict.case["object"],
        "expected" to conflict.case["expected"],
        "actual" to conflict.actual,
        "outcome" to conflict.outcome,
        "semanticHash" to conflict.semanticHash,
        "firstSeenAttempt" to conflict.firstSeenAttempt,
        "readjudicated" to conflict.readjudicated,
    )

    internal fun trackInfo(spec: TrackSpec, response: LlmResponse, promptId: String, promptHash: String, seedOffset: Long) = mapOf(
        "model" to spec.model,
        "servedModel" to response.modelId,
        "promptId" to promptId,
        "promptHash" to promptHash,
        "temperature" to spec.temperature,
        "seed" to spec.seed?.plus(seedOffset),
        "maxTokens" to spec.maxTokens,
        "latencyMs" to response.latencyMs,
        "inputTokens" to response.inputTokens,
        "outputTokens" to response.outputTokens,
        "costUsd" to pricing.costUsd(spec.model, response.inputTokens, response.outputTokens),
    )

    private fun cycleFinished(session: Session, cycle: Int, result: CycleResult, durationMs: Long): Map<String, Any?> {
        val groundTruth = session.ruleSet.groundTruth
        val report = result.finalDmn?.let { strict.validate(it.xml, groundTruth) }
        val calls = result.attempts.flatMap { attempt ->
            listOfNotNull(
                (if (attempt.testsRegenerated) attempt.tests?.response ?: attempt.failedTestsResponse else null)?.let { session.tests to it },
                attempt.dmn?.let { session.dmn to it.response },
            ) + attempt.adjudications.mapNotNull { it.response?.let { response -> session.tests to response } }
        }
        val costs = calls.map { (spec, response) -> pricing.costUsd(spec.model, response.inputTokens, response.outputTokens) }

        return base(session, "cycle_finished") + mapOf(
            "cycle" to cycle,
            "outcome" to result.outcome,
            "attempts" to result.attempts.size,
            "message" to result.message,
            "finalSemanticHash" to result.attempts.lastOrNull { it.semanticHash != null }?.semanticHash,
            "conflictingCases" to result.conflictingCases,
            "openConflictsAtEnd" to result.openConflicts.map(::conflictInfo),
            "groundTruth" to mapOf(
                "evaluated" to (report != null),
                "validator" to ValidatorMode.STRICT,
                "accepted" to report?.accepted,
                "tableError" to report?.tableError,
                "counts" to report?.counts,
                "cases" to report?.cases?.map { case ->
                    mapOf(
                        "index" to case.index,
                        "id" to groundTruth[case.index]["id"],
                        "description" to groundTruth[case.index]["description"],
                        "expected" to groundTruth[case.index]["expected"],
                        "actual" to case.actual,
                        "outcome" to case.outcome,
                        "mismatches" to case.mismatches,
                        "message" to case.message,
                    )
                },
            ),
            "tokens" to mapOf(
                "dmn" to tokenTotals(calls.filter { it.first === session.dmn }.map { it.second }),
                "tests" to tokenTotals(calls.filter { it.first === session.tests }.map { it.second }),
            ),
            "costUsd" to if (costs.any { it == null }) null else costs.sumOf { it!! },
            "durationMs" to durationMs,
        )
    }

    private fun tokenTotals(responses: List<LlmResponse>) =
        mapOf("input" to responses.sumOf { it.inputTokens ?: 0 }, "output" to responses.sumOf { it.outputTokens ?: 0 }, "calls" to responses.size)

    /** Aggregates over the latest `cycle_finished` of every cycle in the file, from all sessions. */
    private fun runFinished(session: Session, events: List<Map<String, Any?>>): Map<String, Any?> {
        val cycles = events.filter { it["event"] == "cycle_finished" }.associateBy { (it["cycle"] as Number).toInt() }.values
        fun gt(cycle: Map<String, Any?>) = cycle["groundTruth"] as Map<*, *>
        fun accepted(cycle: Map<String, Any?>) = cycle["outcome"] == CycleOutcome.ACCEPTED.name
        fun evaluated(cycle: Map<String, Any?>) = gt(cycle)["evaluated"] == true
        fun correct(cycle: Map<String, Any?>) = gt(cycle)["accepted"] == true
        fun tokens(cycle: Map<String, Any?>, track: String, kind: String) =
            ((cycle["tokens"] as Map<*, *>)[track] as Map<*, *>)[kind] as Number

        val costs = cycles.map { it["costUsd"] as Number? }
        return base(session, "run_finished") + mapOf(
            "finishedAt" to Instant.now().toString(),
            "cycles" to cycles.size,
            "outcomes" to cycles.groupingBy { it["outcome"] as String }.eachCount(),
            "attempts" to mapOf(
                "total" to cycles.sumOf { (it["attempts"] as Number).toInt() },
                "meanPerCycle" to cycles.map { (it["attempts"] as Number).toDouble() }.average(),
            ),
            "groundTruth" to mapOf(
                "evaluated" to cycles.count(::evaluated),
                "correct" to cycles.count(::correct),
                "acceptedCorrect" to cycles.count { accepted(it) && correct(it) },
                "acceptedWrong" to cycles.count { accepted(it) && evaluated(it) && !correct(it) },
                "rejectedCorrect" to cycles.count { !accepted(it) && correct(it) },
                "rejectedWrong" to cycles.count { !accepted(it) && evaluated(it) && !correct(it) },
            ),
            "tokens" to listOf("dmn", "tests").associateWith { track ->
                mapOf("input" to cycles.sumOf { tokens(it, track, "input").toLong() }, "output" to cycles.sumOf { tokens(it, track, "output").toLong() })
            },
            "costUsd" to if (costs.any { it == null }) null else costs.sumOf { it!!.toDouble() },
        )
    }
}
