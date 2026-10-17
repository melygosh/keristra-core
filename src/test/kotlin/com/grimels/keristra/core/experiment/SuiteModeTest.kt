package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.grimels.keristra.core.context.DomainRegistry
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.ScriptedLlmClient
import com.grimels.keristra.core.generation.TrackConfig
import com.grimels.keristra.core.generation.Tracks
import com.grimels.keristra.core.generation.TracksProvider
import com.grimels.keristra.core.generation.tests.TestSuiteGenerator
import com.grimels.keristra.core.validation.LenientValidator
import com.grimels.keristra.core.validation.StrictValidator
import com.grimels.keristra.core.validation.testEngine
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

/** REVISION_PLAN step 15: suite-only mode (C0, E1, E4). */
class SuiteModeTest {

    @TempDir
    lateinit var dir: Path

    private val mapper = jacksonObjectMapper()
    private val groundTruth = RuleSetRepository().load("mortgage", "ambiguous", "uk").groundTruth
    private val equivalent = listOf("RULE_SWAP:rule-1/rule-2", "RULE_SWAP:rule-2/rule-3", "RULE_SWAP:rule-4/rule-5")

    /** The test track answers from a script; asking for a DMN track fails the test. */
    private class ScriptedSuites(vararg replies: Any) : TracksProvider {
        val client = ScriptedLlmClient(*replies, modelId = "served-local", inputTokens = 500, outputTokens = 900)
        override fun create(dmn: TrackConfig, tests: TrackConfig): Tracks = throw AssertionError("suite-only mode must not build a DMN track")
        override fun tests(tests: TrackConfig) = TestSuiteGenerator(
            client, PromptTemplate.load("tests", tests.promptVersion), DomainRegistry(), tests.settings,
            PromptTemplate.load("tests-adjudicate", tests.promptVersion),
        )
    }

    private fun runner(tracks: TracksProvider) = ExperimentRunner(
        tracksProvider = tracks,
        ruleSets = RuleSetRepository(),
        domains = DomainRegistry(),
        lenient = LenientValidator(testEngine),
        strict = StrictValidator(testEngine),
        pricing = PricingProperties(freeProviders = setOf("local")),
        gitInfo = { "abc123" },
    )

    private fun config(
        cycles: Int = 2,
        fixedSuites: List<FixedSuiteSpec> = emptyList(),
        tests: TrackSpec? = TrackSpec(model = "local:fake", prompt = "v2", temperature = 0.7, seed = 42),
        evaluate: List<EvaluateSource> = listOf(
            EvaluateSource(reference = "mortgage/ambiguous"),
            EvaluateSource(mutantsOf = "mortgage/ambiguous", equivalentMutants = equivalent),
        ),
    ) = RunConfig(
        runId = "c0-test", ruleSet = "mortgage", version = "ambiguous", language = "uk",
        dmn = null, tests = tests, cycles = cycles, fixedSuites = fixedSuites, evaluate = evaluate,
    )

    private fun events(log: Path): List<Map<String, Any?>> = Files.readAllLines(log).filter { it.isNotBlank() }.map { mapper.readValue(it) }

    private fun json(cases: List<Map<String, Any?>>) = mapper.writeValueAsString(cases)

    @Test
    fun `a config without dmn is suite-only, and cycles mode still needs both tracks`() {
        assertThat(config().effectiveMode).isEqualTo(RunMode.SUITES)
        assertThatThrownBy { config().copy(mode = RunMode.CYCLES) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("dmn")
    }

    @Test
    fun `kill matrix for 2 generated suites x the mortgage reference and its 54 mutants`() {
        val tracks = ScriptedSuites(json(groundTruth), json(groundTruth.filter { it["category"] == "original-v0" }))

        val events = events(runner(tracks).run(config(), dir))

        assertThat(events.first()).containsEntry("event", "run_started").containsEntry("mode", "SUITES")
        val suites = events.filter { it["event"] == "suite" }
        assertThat(suites.map { it["suite"] }).containsExactly("gen-1", "gen-2")
        assertThat(suites[0]).containsEntry("status", "OK").containsEntry("size", 45).containsKeys("raw", "cases", "schemaErrors")
        assertThat(suites[0]["tracks"].obj()["tests"].obj()).containsEntry("servedModel", "served-local").containsEntry("seed", 42 + 1001)
        assertThat(tracks.client.requests.map { it.seed }).containsExactly(1043L, 2043L)

        val evaluations = events.filter { it["event"] == "evaluation" }
        assertThat(evaluations).hasSize(2 * 55)
        val swap = evaluations.first { it["suite"] == "gen-1" && it["dmnId"] == "RULE_SWAP:rule-1/rule-2" }
        assertThat(swap).containsEntry("killed", false).containsEntry("operator", "RULE_SWAP").containsEntry("equivalent", true).containsKeys("counts", "region")

        val summary = events.last()
        assertThat(summary).containsEntry("event", "run_finished")
        val reference = (summary["evaluate"] as List<*>).first().obj()
        assertThat(reference).containsEntry("source", "reference:mortgage/ambiguous").containsEntry("killRate", 0.0)
        val mutants = (summary["evaluate"] as List<*>)[1].obj()
        assertThat(mutants).containsEntry("mutants", 54).containsEntry("equivalent", 3)
        val perSuite = (mutants["perSuite"] as List<*>).map { it.obj() }
        assertThat(perSuite.map { it["killed"] }).containsExactly(51, 29)
        assertThat(perSuite.map { it["score"] as Double }).containsExactly(51.0 / 54, 29.0 / 54)
        assertThat(perSuite.map { it["scoreWithoutEquivalent"] as Double }).containsExactly(1.0, 29.0 / 51)
        assertThat(mutants["medianScoreWithoutEquivalent"] as Double).isCloseTo((1.0 + 29.0 / 51) / 2, Offset.offset(1e-9))
        assertThat(mutants["perOperator"].obj()["RULE_SWAP"].obj()).containsKeys("killRate", "mutants")
    }

    @Test
    fun `the 45-case human ground truth as a fixed suite kills 51 of 54 mortgage mutants`() {
        val fixed = listOf(
            FixedSuiteSpec(groundTruth = "mortgage/ambiguous", name = "H45"),
            FixedSuiteSpec(groundTruth = "mortgage/ambiguous", category = "original-v0", name = "H8"),
        )

        val events = events(runner(ScriptedSuites()).run(config(cycles = 0, fixedSuites = fixed, tests = null), dir))

        assertThat(events.filter { it["event"] == "suite" }.map { it["suite"] to it["size"] }).containsExactly("fixed-H45" to 45, "fixed-H8" to 8)
        val mutants = (events.last()["evaluate"] as List<*>)[1].obj()
        val perSuite = (mutants["perSuite"] as List<*>).map { it.obj() }
        assertThat(perSuite.map { it["suite"] to it["killed"] }).containsExactly("fixed-H45" to 51, "fixed-H8" to 29)
        assertThat(perSuite.first()["scoreWithoutEquivalent"]).isEqualTo(1.0)
    }

    @Test
    fun `an unparseable suite is logged with its raw output and counts as a failed call`() {
        val events = events(runner(ScriptedSuites("no json here", json(groundTruth))).run(config(), dir))

        val failed = events.first { it["event"] == "suite" }
        assertThat(failed).containsEntry("status", "PARSE_ERROR").containsEntry("raw", "no json here").containsKey("error")
        assertThat(events.filter { it["event"] == "evaluation" && it["suite"] == "gen-1" }).isEmpty()
        assertThat(events.last()["suites"].obj()).containsEntry("generated", 2).containsEntry("parseErrors", 1).containsEntry("failedShare", 0.5)
    }

    @Test
    fun `cases whose objects use paths outside the schema are reported as schema errors`() {
        val bad = groundTruth.take(2).toMutableList().apply {
            this[1] = this[1] + mapOf("object" to mapOf("application" to mapOf("creditScore" to 600, "favouriteColour" to "red")))
        }

        val events = events(runner(ScriptedSuites(json(bad), json(groundTruth))).run(config(), dir))

        val suite = events.first { it["event"] == "suite" }
        assertThat((suite["schemaErrors"] as List<*>).single().obj())
            .containsEntry("case", 1).containsEntry("unknownPaths", listOf("application.favouriteColour"))
        assertThat(events.last()["suites"].obj()).containsEntry("withSchemaErrors", 1)
    }

    @Test
    fun `kills by a suite that rejects the reference are also counted on its reference-consistent cases`() {
        val wrong = groundTruth.map { if (it["id"] == 5.0) it + mapOf("expected" to listOf(mapOf("product" to "Fixed30"))) else it }

        val events = events(runner(ScriptedSuites(json(wrong))).run(config(cycles = 1), dir))

        val reference = events.first { it["event"] == "evaluation" && it["dmnId"] == "reference:mortgage/ambiguous" }
        assertThat(reference["killed"]).isEqualTo(true)
        val perSuite = ((events.last()["evaluate"] as List<*>)[1].obj()["perSuite"] as List<*>).single().obj()
        assertThat(perSuite).containsEntry("referenceRejected", true).containsEntry("killed", 54)
        assertThat(perSuite["killedConsistent"] as Int).isLessThan(54)
    }

    @Test
    fun `a rerun resumes, generating only the missing suites`() {
        runner(ScriptedSuites(json(groundTruth))).run(config(cycles = 1), dir)
        val resumed = ScriptedSuites(json(groundTruth))

        val events = events(runner(resumed).run(config(cycles = 2), dir))

        assertThat(resumed.client.requests).hasSize(1)
        assertThat(events.filter { it["event"] == "suite" }.map { it["suite"] }).containsExactly("gen-1", "gen-2")
        assertThat(((events.last()["evaluate"] as List<*>)[1].obj()["perSuite"] as List<*>)).hasSize(2)
    }

    @Test
    fun `evaluate sources are checked before anything runs`() {
        assertThatThrownBy { EvaluateSource(reference = "mortgage/ambiguous", file = "x.dmn") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("exactly one")
        assertThatThrownBy {
            runner(ScriptedSuites()).run(
                config(evaluate = listOf(EvaluateSource(mutantsOf = "mortgage/ambiguous", equivalentMutants = listOf("RULE_SWAP:rule-9/rule-10")))),
                dir,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("RULE_SWAP:rule-9/rule-10")
    }

    @Test
    fun `a file source evaluates a DMN from disk`() {
        val file = dir.resolve("draft.dmn")
        Files.write(file, javaClass.getResourceAsStream("/rulesets/mortgage/ambiguous/reference.draft-sonnet.dmn")!!.readBytes())

        val events = events(
            runner(ScriptedSuites(json(groundTruth))).run(config(cycles = 1, evaluate = listOf(EvaluateSource(file = file.toString()))), dir),
        )

        val evaluation = events.single { it["event"] == "evaluation" }
        assertThat(evaluation).containsEntry("killed", true).containsEntry("dmnId", "file:$file")
        assertThat(evaluation["counts"].obj()).containsEntry("MISMATCH", 8)
    }

    @Test
    fun `the documented suite-only JSON reads`() {
        val json = """{"runId": "c0-gemma4", "ruleSet": "mortgage", "version": "ambiguous", "language": "uk", "mode": "SUITES",
            "tests": {"model": "local:gemma4:26b", "prompt": "v2", "temperature": 0.7, "seed": 42},
            "cycles": 10,
            "evaluate": [{"mutantsOf": "mortgage/ambiguous", "equivalentMutants": ["RULE_SWAP:rule-1/rule-2"]}]}"""
        val file = dir.resolve("c0.json").also { Files.writeString(it, json) }

        val config = RunConfig.read(file)

        assertThat(config.effectiveMode).isEqualTo(RunMode.SUITES)
        assertThat(config.dmn).isNull()
        assertThat(config.evaluate.single().equivalentMutants).containsExactly("RULE_SWAP:rule-1/rule-2")
    }
}
