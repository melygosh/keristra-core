package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.databind.ObjectMapper
import com.grimels.keristra.core.context.DomainRegistry
import com.grimels.keristra.core.generation.tests.TestSuiteGenerator
import com.grimels.keristra.core.generation.tests.TestSuiteParseException
import com.grimels.keristra.core.mutation.DmnMutator
import com.grimels.keristra.core.mutation.MutationOperator
import com.grimels.keristra.core.validation.CaseOutcome
import com.grimels.keristra.core.validation.Validator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Suite-only runs (REVISION_PLAN step 15; C0, E1, E4 in docs/EXPERIMENTS.md): no DMN track. The test track generates
 * suites (`gen-<i>`) until `cycles` of them parse, at most 2 x `cycles` attempts; an unparseable suite is logged as
 * `PARSE_ERROR` and not counted. Fixed human suites can be added (`fixed-<name>`), and every valid suite is evaluated against
 * every DMN of the `evaluate` sources. Events: `run_started`, then per suite a `suite` event followed by its
 * `evaluation` events, then `run_finished` with the summaries over every suite in the file.
 *
 * Kill = the suite rejects the DMN (protocol 4.1). For mutants the log also carries the kill on the suite's
 * reference-consistent cases only (cases that pass on the reference), because a suite that rejects the reference
 * kills every mutant by definition.
 */
internal class SuiteRun(
    private val ruleSets: RuleSetRepository,
    private val domains: DomainRegistry,
    private val validator: Validator,
    private val testsGenerator: TestSuiteGenerator?,
    private val gitInfo: GitInfo,
    private val mapper: ObjectMapper,
    private val trackInfo: (TrackSpec, com.grimels.keristra.core.llm.LlmResponse, String, String, Long) -> Map<String, Any?>,
) {

    private val logger = LoggerFactory.getLogger(SuiteRun::class.java)

    /** One DMN to evaluate; for mutants, [referenceXml] is the reference they come from. */
    private class Target(
        val source: String,
        val id: String,
        val xml: String,
        val operator: MutationOperator? = null,
        val region: Map<String, Any?>? = null,
        val equivalent: Boolean = false,
        val referenceXml: String? = null,
    )

    private class Source(val name: String, val kind: String, val targets: List<Target>, val equivalentIds: Set<String>)

    fun run(config: RunConfig, logFile: Path, previous: List<Map<String, Any?>>): Path {
        val ruleSet = ruleSets.load(config.ruleSet, config.version, config.language)
        val sources = config.evaluate.map(::plan)
        val targetCount = sources.sumOf { it.targets.size }
        val schemaPaths = domains.schemaPaths(ruleSet.domain)
        val sessionId = UUID.randomUUID().toString()

        val latestSuites = previous.filter { it["event"] == "suite" }.associateBy { it["suite"] as String }
        val evaluatedTargets = previous.filter { it["event"] == "evaluation" }.groupBy { it["suite"] as String }.mapValues { (_, e) -> e.map { it["dmnId"] }.toSet().size }
        fun done(id: String): Boolean {
            val suite = latestSuites[id] ?: return false
            return when (suite["status"]) {
                "PARSE_ERROR" -> true
                "OK" -> (evaluatedTargets[id] ?: 0) >= targetCount
                else -> false
            }
        }

        val fixed = config.fixedSuites.mapIndexed { i, spec -> "fixed-${spec.name ?: (i + 1)}" to spec }
        // Unparseable suites are logged but not counted (EXPERIMENTS.md section 9, 2026-10-18): attempts go on until
        // `cycles` valid suites exist, at most 2 x cycles of them.
        val generated = (1..2 * config.cycles).map { "gen-$it" to it }
        val skipped = (fixed.map { it.first } + generated.map { it.first }).filter(::done)

        fun base(event: String) = linkedMapOf<String, Any?>("event" to event, "runId" to config.runId, "sessionId" to sessionId)

        JsonlLog.Writer(logFile, mapper).use { out ->
            out.append(
                base("run_started") + mapOf(
                    "startedAt" to Instant.now().toString(),
                    "mode" to RunMode.SUITES,
                    "resumed" to previous.isNotEmpty(),
                    "skippedSuites" to skipped,
                    "config" to config,
                    "gitCommit" to gitInfo.commit(),
                    "models" to mapOf("dmn" to null, "tests" to config.tests?.model),
                    "prompts" to mapOf(
                        "dmn" to null,
                        "tests" to testsGenerator?.let { mapOf("id" to it.prompt.id, "hash" to it.prompt.hash) },
                    ),
                    "ruleSet" to mapOf(
                        "id" to ruleSet.id, "language" to ruleSet.language, "hash" to ruleSet.hash,
                        "domain" to ruleSet.domain, "groundTruthSize" to ruleSet.groundTruth.size,
                    ),
                    "evaluate" to sources.map {
                        mapOf("source" to it.name, "kind" to it.kind, "dmns" to it.targets.size, "equivalentMutants" to it.equivalentIds.sorted())
                    },
                    "runtime" to mapOf("java" to System.getProperty("java.version")),
                ),
            )

            fun evaluateSuite(id: String, cases: List<Map<String, Any?>>) {
                val referenceCache = mutableMapOf<String, Set<Int>>()
                for (source in sources) for (target in source.targets) {
                    val report = validator.validate(target.xml, cases)
                    val failing = report.cases.filter { it.outcome != CaseOutcome.PASS }.map { it.index }
                    val event = base("evaluation") + mapOf(
                        "suite" to id,
                        "source" to source.name,
                        "dmnId" to target.id,
                        "operator" to target.operator,
                        "region" to target.region,
                        "equivalent" to target.equivalent,
                        "killed" to !report.accepted,
                        "counts" to report.counts,
                        "tableError" to report.tableError,
                        "failingCases" to failing,
                    )
                    val consistent = target.referenceXml?.let { referenceXml ->
                        val referenceFailing = referenceCache.getOrPut(referenceXml) {
                            validator.validate(referenceXml, cases).cases.filter { it.outcome != CaseOutcome.PASS }.map { it.index }.toSet()
                        }
                        mapOf(
                            "referenceRejected" to referenceFailing.isNotEmpty(),
                            "referenceFailingCases" to referenceFailing.sorted(),
                            "killedConsistent" to (report.tableError != null || failing.any { it !in referenceFailing }),
                        )
                    }.orEmpty()
                    out.append(event + consistent)
                }
            }

            for ((id, spec) in fixed) {
                if (id in skipped) continue
                val (name, version) = splitRef(spec.groundTruth)
                val cases = ruleSets.load(name, version, config.language).groundTruth
                    .filter { spec.category == null || it["category"] == spec.category }
                out.append(
                    base("suite") + mapOf(
                        "suite" to id, "source" to "fixed", "groundTruth" to spec.groundTruth, "category" to spec.category,
                        "status" to "OK", "size" to cases.size, "cases" to cases, "schemaErrors" to schemaErrors(cases, schemaPaths),
                    ),
                )
                evaluateSuite(id, cases)
            }

            var valid = 0
            for ((id, index) in generated) {
                if (valid >= config.cycles) break
                val earlier = latestSuites[id]
                when (earlier?.get("status")) {
                    "OK" -> {
                        // Generated in an earlier session; if a crash cut its evaluations short, evaluate the logged
                        // suite again, without a new call.
                        @Suppress("UNCHECKED_CAST")
                        if (id !in skipped) evaluateSuite(id, earlier["cases"] as List<Map<String, Any?>>)
                        valid++
                        continue
                    }
                    "PARSE_ERROR" -> continue // a spent attempt; an ERROR attempt is retried on resume
                }
                val generator = checkNotNull(testsGenerator)
                val spec = checkNotNull(config.tests)
                val seedOffset = index * 1000L + 1
                val event = base("suite") + mapOf("suite" to id, "source" to "generated", "index" to index, "seedOffset" to seedOffset)
                val outcome = try {
                    val suite = generator.generate(ruleSet.rules, ruleSet.domain, seedOffset)
                    mapOf(
                        "status" to "OK",
                        "tracks" to mapOf("tests" to trackInfo(spec, suite.response, suite.promptId, suite.promptHash, seedOffset)),
                        "size" to suite.cases.size,
                        "cases" to suite.cases,
                        "raw" to suite.response.text,
                        "schemaErrors" to schemaErrors(suite.cases, schemaPaths),
                    ) to suite.cases
                } catch (e: TestSuiteParseException) {
                    mapOf(
                        "status" to "PARSE_ERROR",
                        "tracks" to mapOf("tests" to trackInfo(spec, e.response, e.promptId, generator.prompt.hash, seedOffset)),
                        "raw" to e.response.text,
                        "error" to e.message,
                    ) to null
                } catch (e: Exception) {
                    mapOf("status" to "ERROR", "error" to "${e.javaClass.simpleName}: ${e.message}") to null
                }
                out.append(event + outcome.first)
                outcome.second?.let { evaluateSuite(id, it); valid++ }
                logger.info("[${config.runId}] suite $id: ${outcome.first["status"]}")
            }

            out.append(base("run_finished") + summary(JsonlLog.read(logFile, mapper), sources, config.cycles))
        }
        return logFile
    }

    private fun plan(source: EvaluateSource): Source = when {
        source.reference != null ->
            Source("reference:${source.reference}", "reference", listOf(Target("reference:${source.reference}", "reference:${source.reference}", referenceXml(source.reference))), emptySet())
        source.file != null ->
            Source("file:${source.file}", "file", listOf(Target("file:${source.file}", "file:${source.file}", Files.readString(Path.of(source.file)))), emptySet())
        else -> {
            val ref = source.mutantsOf!!
            val xml = referenceXml(ref)
            val mutants = DmnMutator.mutants(xml, source.operators?.toSet() ?: MutationOperator.values().toSet())
            val unknown = source.equivalentMutants.toSet() - mutants.map { it.id }.toSet()
            require(unknown.isEmpty()) { "equivalentMutants not among the mutants of $ref: ${unknown.sorted()}" }
            val name = "mutants:$ref"
            Source(
                name, "mutants",
                mutants.map {
                    Target(
                        name, it.id, it.xml, it.operator,
                        mapOf("ruleIds" to it.region.ruleIds, "input" to it.region.input, "before" to it.region.before, "after" to it.region.after),
                        it.id in source.equivalentMutants, xml,
                    )
                },
                source.equivalentMutants.toSet(),
            )
        }
    }

    private fun splitRef(ref: String): Pair<String, String> {
        require('/' in ref) { "Expected <ruleSet>/<version>, got '$ref'" }
        return ref.substringBeforeLast('/') to ref.substringAfterLast('/')
    }

    private fun referenceXml(ref: String): String {
        val (name, version) = splitRef(ref)
        return SuiteRun::class.java.getResourceAsStream("/rulesets/$name/$version/reference.dmn")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("No reference.dmn for $ref")
    }

    /** Cases whose object uses paths outside the domain schema, or that miss `object` / `expected`. */
    private fun schemaErrors(cases: List<Map<String, Any?>>, schemaPaths: Set<String>?): List<Map<String, Any?>> =
        cases.mapIndexedNotNull { i, case ->
            val missing = listOf("object", "expected").filter { it !in case }
            val unknown = if (schemaPaths == null) emptyList() else paths(case["object"], null).filter { it !in schemaPaths }.distinct().sorted()
            if (missing.isEmpty() && unknown.isEmpty()) null else mapOf("case" to i, "unknownPaths" to unknown, "missing" to missing)
        }

    private fun paths(value: Any?, prefix: String?): List<String> = when (value) {
        is Map<*, *> -> value.entries.flatMap { (k, v) ->
            val path = if (prefix == null) "$k" else "$prefix.$k"
            listOf(path) + paths(v, path)
        }
        is List<*> -> value.flatMap { if (it is Map<*, *>) paths(it, prefix) else emptyList() }
        else -> emptyList()
    }

    private fun summary(events: List<Map<String, Any?>>, sources: List<Source>, target: Int): Map<String, Any?> {
        val suites = events.filter { it["event"] == "suite" }.associateBy { it["suite"] as String }.values.toList()
        val generated = suites.filter { it["source"] == "generated" }
        fun tests(suite: Map<String, Any?>) = ((suite["tracks"] as? Map<*, *>)?.get("tests") as? Map<*, *>)
        val costs = generated.mapNotNull { tests(it) }.map { it["costUsd"] as Number? }
        val failed = generated.count { it["status"] != "OK" }
        val ok = generated.count { it["status"] == "OK" }
        val parseErrors = generated.count { it["status"] == "PARSE_ERROR" }

        val evaluations = events.filter { it["event"] == "evaluation" }
            .associateBy { "${it["suite"]} ${it["dmnId"]}" }.values.toList()
        val suiteOrder = suites.filter { it["status"] == "OK" }.map { it["suite"] as String }
        val suiteSizes = suites.filter { it["status"] == "OK" }.associate { it["suite"] as String to (it["size"] as Number).toInt() }

        return mapOf(
            "finishedAt" to Instant.now().toString(),
            "suites" to mapOf(
                "fixed" to suites.count { it["source"] == "fixed" },
                "target" to target,
                "attempts" to generated.size,
                "generated" to generated.size,
                "ok" to ok,
                "parseErrors" to parseErrors,
                "errors" to generated.count { it["status"] == "ERROR" },
                // Parse-error rate of the model: unparseable / (valid + unparseable); infrastructure errors excluded.
                "parseFailureShare" to if (ok + parseErrors == 0) null else parseErrors.toDouble() / (ok + parseErrors),
                "budgetExhausted" to (ok < target),
                "failedShare" to if (generated.isEmpty()) null else failed.toDouble() / generated.size,
                "medianLatencyMs" to median(generated.mapNotNull { (tests(it)?.get("latencyMs") as? Number)?.toDouble() }),
                "medianSize" to median(generated.filter { it["status"] == "OK" }.map { (it["size"] as Number).toDouble() }),
                "withSchemaErrors" to suites.count { it["status"] == "OK" && (it["schemaErrors"] as? List<*>).orEmpty().isNotEmpty() },
                "tokens" to mapOf(
                    "input" to generated.sumOf { (tests(it)?.get("inputTokens") as? Number)?.toLong() ?: 0L },
                    "output" to generated.sumOf { (tests(it)?.get("outputTokens") as? Number)?.toLong() ?: 0L },
                ),
                "costUsd" to if (costs.any { it == null }) null else costs.sumOf { it!!.toDouble() },
            ),
            "evaluate" to sources.map { source -> sourceSummary(source, evaluations.filter { it["source"] == source.name }, suiteOrder, suiteSizes) },
        )
    }

    private fun sourceSummary(
        source: Source, evaluations: List<Map<String, Any?>>, suiteOrder: List<String>, suiteSizes: Map<String, Int>,
    ): Map<String, Any?> {
        val bySuite = evaluations.groupBy { it["suite"] as String }
        val suiteIds = suiteOrder.filter { it in bySuite }
        if (source.kind != "mutants") {
            val killed = suiteIds.count { id -> bySuite.getValue(id).single()["killed"] == true }
            return mapOf(
                "source" to source.name, "kind" to source.kind, "suites" to suiteIds.size, "killed" to killed,
                "killRate" to if (suiteIds.isEmpty()) null else killed.toDouble() / suiteIds.size,
                "killedBy" to suiteIds.filter { id -> bySuite.getValue(id).single()["killed"] == true },
            )
        }
        val total = source.targets.size
        val nonEquivalent = total - source.equivalentIds.size
        fun ratio(n: Int, d: Int) = if (d == 0) null else n.toDouble() / d
        val perSuite = suiteIds.map { id ->
            val rows = bySuite.getValue(id)
            val killed = rows.filter { it["killed"] == true }
            val killedConsistent = rows.filter { it["killedConsistent"] == true }
            val referenceFailing = (rows.firstOrNull()?.get("referenceFailingCases") as? List<*>).orEmpty().size
            mapOf(
                "suite" to id,
                "referenceRejected" to (rows.firstOrNull()?.get("referenceRejected") == true),
                // Oracle disagreement with the human reference: share of the suite's cases the reference fails.
                "referenceFailing" to referenceFailing,
                "referenceDisagreement" to ratio(referenceFailing, suiteSizes[id] ?: 0),
                "killed" to killed.size,
                "killedConsistent" to killedConsistent.size,
                "score" to ratio(killed.size, total),
                "scoreWithoutEquivalent" to ratio(killed.count { it["equivalent"] != true }, nonEquivalent),
                "scoreConsistent" to ratio(killedConsistent.size, total),
                "scoreConsistentWithoutEquivalent" to ratio(killedConsistent.count { it["equivalent"] != true }, nonEquivalent),
            )
        }
        fun medianOf(key: String) = median(perSuite.mapNotNull { it[key] as Double? })
        val byOperator = evaluations.groupBy { it["operator"] as String }
        return mapOf(
            "source" to source.name, "kind" to source.kind, "suites" to suiteIds.size,
            "mutants" to total, "equivalent" to source.equivalentIds.size,
            "perSuite" to perSuite,
            "medianScore" to medianOf("score"),
            "medianScoreWithoutEquivalent" to medianOf("scoreWithoutEquivalent"),
            "medianScoreConsistent" to medianOf("scoreConsistent"),
            "medianScoreConsistentWithoutEquivalent" to medianOf("scoreConsistentWithoutEquivalent"),
            "medianReferenceDisagreement" to medianOf("referenceDisagreement"),
            "suitesRejectingReference" to ratio(perSuite.count { it["referenceRejected"] == true }, perSuite.size),
            "perOperator" to byOperator.toSortedMap().mapValues { (_, rows) ->
                mapOf(
                    "mutants" to rows.map { it["dmnId"] }.distinct().size,
                    "killRate" to rows.count { it["killed"] == true }.toDouble() / rows.size,
                    "killRateConsistent" to rows.count { it["killedConsistent"] == true }.toDouble() / rows.size,
                )
            },
            "perMutant" to evaluations.groupBy { it["dmnId"] as String }.toSortedMap().mapValues { (_, rows) ->
                rows.count { it["killed"] == true }.toDouble() / rows.size
            },
        )
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
