package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.grimels.keristra.core.context.DomainRegistry
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.ScriptedLlmClient
import com.grimels.keristra.core.generation.Tracks
import com.grimels.keristra.core.generation.TracksProvider
import com.grimels.keristra.core.generation.dmn.DmnGenerator
import com.grimels.keristra.core.generation.tests.TestSuiteGenerator
import com.grimels.keristra.core.orchestration.RepairStrategy
import com.grimels.keristra.core.validation.In
import com.grimels.keristra.core.validation.LenientValidator
import com.grimels.keristra.core.validation.Out
import com.grimels.keristra.core.validation.StrictValidator
import com.grimels.keristra.core.validation.ValidatorMode
import com.grimels.keristra.core.validation.app
import com.grimels.keristra.core.validation.dmn
import com.grimels.keristra.core.validation.firstNoDefault
import com.grimels.keristra.core.validation.testEngine
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

class ExperimentRunnerTest {

    @TempDir
    lateinit var dir: Path

    /** Matches the toy rule set's ground truth: Fixed30 for everyone. */
    private val goodDmn = dmn(
        "FIRST",
        listOf(In("application.fixedPreference", "boolean"), In("application.creditScore", "double")),
        Out("product"),
        listOf(listOf("true", "") to "\"Fixed30\"", listOf("", "") to "\"Fixed30\""),
    )
    private val suite = """[{"object": ${app(true, 650)}, "expected": [{"product": "Fixed30"}]},
                            {"object": ${app(false, 720)}, "expected": [{"product": "Fixed30"}]}]"""

    private val mapper = jacksonObjectMapper()

    private val pricing = PricingProperties(
        models = mapOf("priced-model" to PricingProperties.ModelPrice(inputPerMtok = 2.0, outputPerMtok = 10.0)),
        freeProviders = setOf("local"),
    )

    private fun config(
        runId: String = "toy-run",
        cycles: Int = 2,
        validator: ValidatorMode = ValidatorMode.STRICT,
        strategy: RepairStrategy = RepairStrategy.PAIRED_REGEN,
        conflictMemory: Boolean = false,
        retryBudget: Int = 3,
    ) = RunConfig(
        runId = runId, ruleSet = "toy", version = "v1", language = "en",
        dmn = TrackSpec(model = "anthropic:priced-model"),
        tests = TrackSpec(model = "local:free-model", temperature = 0.7, seed = 42),
        validator = validator, strategy = strategy, cycles = cycles, conflictMemory = conflictMemory, retryBudget = retryBudget,
    )

    /** Each created track pair answers from its own script. */
    private class ScriptedTracks(val dmnReplies: List<Any>, val testReplies: List<Any>) : TracksProvider {
        val dmnClients = mutableListOf<ScriptedLlmClient>()
        val testsClients = mutableListOf<ScriptedLlmClient>()

        override fun create(dmn: com.grimels.keristra.core.generation.TrackConfig, tests: com.grimels.keristra.core.generation.TrackConfig): Tracks {
            val dmnClient = ScriptedLlmClient(*dmnReplies.toTypedArray(), modelId = "served-dmn", inputTokens = 1000, outputTokens = 500)
            val testsClient = ScriptedLlmClient(*testReplies.toTypedArray(), modelId = "served-tests", inputTokens = 300, outputTokens = 200)
            dmnClients += dmnClient
            testsClients += testsClient
            val registry = DomainRegistry()
            return Tracks(
                DmnGenerator(dmnClient, PromptTemplate.load("dmn", dmn.promptVersion), registry, dmn.settings),
                TestSuiteGenerator(
                    testsClient, PromptTemplate.load("tests", tests.promptVersion), registry, tests.settings,
                    PromptTemplate.load("tests-adjudicate", "v1"),
                ),
            )
        }
    }

    private fun runner(tracks: TracksProvider) = ExperimentRunner(
        tracksProvider = tracks,
        ruleSets = RuleSetRepository(),
        domains = DomainRegistry(),
        lenient = LenientValidator(testEngine),
        strict = StrictValidator(testEngine),
        pricing = pricing,
        gitInfo = { "abc123-dirty" },
    )

    private fun events(log: Path): List<Map<String, Any?>> =
        Files.readAllLines(log).filter { it.isNotBlank() }.map { mapper.readValue(it) }

    @Test
    fun `writes run_started, attempt, cycle_finished and run_finished with the section 5 fields`() {
        val log = runner(ScriptedTracks(listOf(goodDmn, goodDmn), listOf(suite, suite))).run(config(), dir)

        val events = events(log)
        assertThat(log.fileName.toString()).isEqualTo("toy-run.jsonl")
        assertThat(events.map { it["event"] })
            .containsExactly("run_started", "attempt", "cycle_finished", "attempt", "cycle_finished", "run_finished")
        assertThat(events.map { it["sessionId"] }.distinct()).hasSize(1)

        val started = events[0]
        assertThat(started).containsKeys("runId", "config", "gitCommit", "models", "prompts", "ruleSet", "startedAt")
        assertThat(started["gitCommit"]).isEqualTo("abc123-dirty")
        assertThat(started["models"]).isEqualTo(mapOf("dmn" to "anthropic:priced-model", "tests" to "local:free-model"))
        assertThat((started["prompts"].obj()).keys).containsExactly("dmn", "tests")
        assertThat((started["prompts"].obj())["dmn"].obj()).containsEntry("id", "dmn/v1").containsKey("hash")
        assertThat(started["ruleSet"].obj()).containsEntry("id", "toy/v1").containsKey("hash")

        val attempt = events[1]
        assertThat(attempt).containsEntry("cycle", 1).containsEntry("attempt", 1)
            .containsKeys("tracks", "semanticHash", "testSuiteSize", "outcomes", "counts", "dmnXml", "testSuite", "seedOffset")
        val tracks = attempt["tracks"].obj()
        assertThat(tracks["dmn"].obj()).containsEntry("servedModel", "served-dmn")
            .containsEntry("inputTokens", 1000).containsEntry("outputTokens", 500).containsKeys("latencyMs", "promptHash")
        assertThat(tracks["tests"].obj()).containsEntry("seed", 42 + 1001)
        assertThat(attempt["testSuiteSize"]).isEqualTo(2)
        assertThat(attempt["dmnXml"]).isEqualTo(goodDmn)

        val finished = events[2]
        assertThat(finished).containsEntry("outcome", "ACCEPTED").containsEntry("attempts", 1).containsKeys("costUsd", "groundTruth", "durationMs")
        val groundTruth = finished["groundTruth"].obj()
        assertThat(groundTruth).containsEntry("evaluated", true).containsEntry("accepted", true)
        assertThat((groundTruth["cases"] as List<*>).first().obj()).containsKeys("expected", "actual", "outcome")

        val summary = events.last()
        assertThat(summary).containsEntry("cycles", 2).containsKeys("outcomes", "groundTruth", "tokens", "costUsd")
        assertThat(summary["outcomes"]).isEqualTo(mapOf("ACCEPTED" to 2))
    }

    @Test
    fun `ground truth is always checked strictly, so a lenient false accept is counted`() {
        val f1Suite = """[{"object": ${app(false, 650)}, "expected": [{"product": "Fixed30"}]}]"""
        val log = runner(ScriptedTracks(listOf(firstNoDefault), listOf(f1Suite)))
            .run(config(cycles = 1, validator = ValidatorMode.LENIENT), dir)

        val events = events(log)
        val finished = events.single { it["event"] == "cycle_finished" }
        assertThat(finished["outcome"]).isEqualTo("ACCEPTED")
        assertThat((finished["groundTruth"].obj())["accepted"]).isEqualTo(false)
        assertThat((events.last()["groundTruth"].obj())["acceptedWrong"]).isEqualTo(1)
    }

    @Test
    fun `cost uses configured prices, free providers cost nothing`() {
        val log = runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(config(cycles = 1), dir)

        val finished = events(log).single { it["event"] == "cycle_finished" }
        assertThat(finished["costUsd"] as Double).isCloseTo(0.007, org.assertj.core.data.Offset.offset(1e-12))
    }

    @Test
    fun `a rerun resumes, skipping cycles that already finished`() {
        runner(ScriptedTracks(listOf(goodDmn, goodDmn), listOf(suite, suite))).run(config(cycles = 2), dir)
        val resumed = ScriptedTracks(listOf(goodDmn), listOf(suite))

        val log = runner(resumed).run(config(cycles = 3), dir)

        val events = events(log)
        val starts = events.filter { it["event"] == "run_started" }
        assertThat(starts).hasSize(2)
        assertThat(starts[1]).containsEntry("resumed", true).containsEntry("skippedCycles", listOf(1, 2))
        assertThat(resumed.dmnClients.single().requests).hasSize(1)
        assertThat(events.filter { it["event"] == "cycle_finished" }.map { it["cycle"] }).containsExactly(1, 2, 3)
        assertThat(events.last()).containsEntry("event", "run_finished").containsEntry("cycles", 3)
    }

    @Test
    fun `a rerun retries cycles that ended in ERROR - infrastructure failures are not results`() {
        runner(ScriptedTracks(listOf(RuntimeException("UnresolvedModelServerException")), listOf(suite))).run(config(cycles = 1), dir)
        val retry = ScriptedTracks(listOf(goodDmn), listOf(suite))

        val log = runner(retry).run(config(cycles = 1), dir)

        val events = events(log)
        assertThat(events.filter { it["event"] == "run_started" }[1]).containsEntry("skippedCycles", emptyList<Int>())
        assertThat(events.filter { it["event"] == "cycle_finished" }.map { it["outcome"] }).containsExactly("ERROR", "ACCEPTED")
        assertThat(events.last()["outcomes"]).isEqualTo(mapOf("ACCEPTED" to 1))
    }

    @Test
    fun `a run id cannot be resumed with a different config`() {
        runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(config(cycles = 1), dir)

        assertThatThrownBy { runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(config(cycles = 1, strategy = RepairStrategy.STATIC_ORACLE), dir) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("toy-run")
            .hasMessageContaining("strategy")
    }

    @Test
    fun `RAG is rejected until it is implemented`() {
        assertThatThrownBy { runner(ScriptedTracks(emptyList(), emptyList())).run(config().copy(rag = RagSpec(enabled = true)), dir) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("RAG")
    }

    @Test
    fun `run config reads the documented JSON and rejects unknown fields`() {
        val json = """{"runId": "r1", "ruleSet": "mortgage", "version": "ambiguous", "language": "en",
            "dmn": {"model": "anthropic:claude-sonnet-5-5", "maxTokens": 16000},
            "tests": {"model": "local:gemma4:26b", "temperature": 0.7, "seed": 42},
            "validator": "STRICT", "strategy": "ESCALATE_ON_STABLE_DISAGREEMENT", "retryBudget": 3, "conflictMemory": true, "rag": {"enabled": false}, "cycles": 3}"""
        val file = dir.resolve("r1.json").also { Files.writeString(it, json) }

        val config = RunConfig.read(file)

        assertThat(config.tests.temperature).isEqualTo(0.7)
        assertThat(config.dmn.temperature).isNull()
        assertThat(config.strategy).isEqualTo(RepairStrategy.ESCALATE_ON_STABLE_DISAGREEMENT)
        assertThat(config.conflictMemory).isTrue()
        Files.writeString(file, json.replace("\"seed\"", "\"sead\""))
        assertThatThrownBy { RunConfig.read(file) }.hasMessageContaining("sead")
    }

    @Test
    fun `conflict memory is logged - open and closed conflicts, adjudications, open conflicts at the end`() {
        val withoutConflict = """[{"object": ${app(true, 650)}, "expected": [{"product": "Fixed30"}]}]"""
        val insist = """[{"object": ${app(false, 720)}, "expected": [{"product": "Fixed30"}]}]"""
        val log = runner(ScriptedTracks(listOf(firstNoDefault, firstNoDefault), listOf(suite, withoutConflict, insist)))
            .run(config(cycles = 1, conflictMemory = true, retryBudget = 2), dir)

        val events = events(log)
        assertThat(events[0]["config"].obj()).containsEntry("conflictMemory", true)
        val second = events.filter { it["event"] == "attempt" }[1]
        assertThat(second).containsEntry("accepted", false).containsEntry("suiteAccepted", true)
        assertThat(second["openConflicts"] as List<*>).hasSize(1)
        assertThat(second["closedConflicts"] as List<*>).isEmpty()
        val adjudication = (second["adjudications"] as List<*>).single().obj()
        assertThat(adjudication).containsEntry("agreesWithDmn", false).containsKeys("previousExpected", "newExpected")
        assertThat(adjudication["track"].obj()).containsEntry("servedModel", "served-tests").containsEntry("inputTokens", 300).containsEntry("promptId", "tests-adjudicate/v1")
        val finished = events.single { it["event"] == "cycle_finished" }
        assertThat(finished["outcome"]).isEqualTo("REJECTED_BUDGET")
        val atEnd = (finished["openConflictsAtEnd"] as List<*>).single().obj()
        assertThat(atEnd).containsEntry("expected", listOf(mapOf("product" to "Fixed30"))).containsEntry("actual", listOf(mapOf("product" to "ARM7/1")))
        assertThat(finished["tokens"].obj()["tests"].obj()).containsEntry("calls", 3)
    }

    @Test
    fun `a log written before conflictMemory existed can still be resumed`() {
        val log = runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(config(cycles = 1), dir)
        val legacy = Files.readAllLines(log).map { line ->
            val event: MutableMap<String, Any?> = mapper.readValue(line)
            (event["config"] as? MutableMap<*, *>)?.remove("conflictMemory")
            mapper.writeValueAsString(event)
        }
        Files.write(log, legacy)

        runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(config(cycles = 2), dir)

        assertThat(events(log).filter { it["event"] == "cycle_finished" }.map { it["cycle"] }).containsExactly(1, 2)
    }
}
