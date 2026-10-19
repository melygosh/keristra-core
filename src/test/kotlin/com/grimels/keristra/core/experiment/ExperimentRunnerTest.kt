package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.grimels.keristra.core.context.DomainRegistry
import com.grimels.keristra.core.generation.ContextRetriever
import com.grimels.keristra.core.generation.PromptTemplate
import com.grimels.keristra.core.generation.RetrievedSegment
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
        version: String = "v1",
        escalateOnAmbiguityFlags: Boolean = false,
    ) = RunConfig(
        runId = runId, ruleSet = "toy", version = version, language = "en", escalateOnAmbiguityFlags = escalateOnAmbiguityFlags,
        dmn = TrackSpec(model = "anthropic:priced-model"),
        tests = TrackSpec(model = "local:free-model", temperature = 0.7, seed = 42),
        validator = validator, strategy = strategy, cycles = cycles, conflictMemory = conflictMemory, retryBudget = retryBudget,
    )

    /** Each created track pair answers from its own script. */
    private class ScriptedTracks(val dmnReplies: List<Any>, val testReplies: List<Any>) : TracksProvider {
        override fun tests(tests: com.grimels.keristra.core.generation.TrackConfig) = create(tests, tests).tests

        val dmnClients = mutableListOf<ScriptedLlmClient>()
        val testsClients = mutableListOf<ScriptedLlmClient>()

        override fun create(dmn: com.grimels.keristra.core.generation.TrackConfig, tests: com.grimels.keristra.core.generation.TrackConfig): Tracks {
            val dmnClient = ScriptedLlmClient(*dmnReplies.toTypedArray(), modelId = "served-dmn", inputTokens = 1000, outputTokens = 500)
            val testsClient = ScriptedLlmClient(*testReplies.toTypedArray(), modelId = "served-tests", inputTokens = 300, outputTokens = 200)
            dmnClients += dmnClient
            testsClients += testsClient
            val registry = DomainRegistry()
            val retriever = dmn.rag?.let {
                object : ContextRetriever {
                    override val description = mapOf("embeddingModel" to it.embeddingModel, "corpusHash" to "abc", "k" to it.k)
                    override fun retrieve(query: String) = listOf(RetrievedSegment("DMN.txt#7", 0.83, "FEEL syntax"))
                }
            }
            return Tracks(
                DmnGenerator(dmnClient, PromptTemplate.load("dmn", dmn.promptVersion), registry, dmn.settings, retriever),
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

    // --- ERROR cycles are infrastructure failures (EXPERIMENTS.md 4.2): replaced until `cycles` valid cycles exist ---

    @Test
    fun `an ERROR cycle is replaced by a new cycle number until cycles valid cycles exist`() {
        val tracks = ScriptedTracks(listOf(RuntimeException("Connection reset"), goodDmn, goodDmn), listOf(suite, suite, suite))

        val log = runner(tracks).run(config(cycles = 2), dir)

        val events = events(log)
        val finished = events.filter { it["event"] == "cycle_finished" }
        assertThat(finished.map { it["cycle"] }).containsExactly(1, 2, 3)
        assertThat(finished.map { it["outcome"] }).containsExactly("ERROR", "ACCEPTED", "ACCEPTED")
        val summary = events.last()
        assertThat(summary).containsEntry("target", 2).containsEntry("validCycles", 2).containsEntry("errorCycles", 1)
            .containsEntry("budgetExhausted", false)
        assertThat(summary["outcomes"]).isEqualTo(mapOf("ERROR" to 1, "ACCEPTED" to 2))
        assertThat(summary["groundTruth"].obj()).containsEntry("evaluated", 2).containsEntry("acceptedCorrect", 2)
        assertThat(summary["attempts"].obj()).containsEntry("total", 2)
        // The failed cycle's calls are still paid for.
        assertThat(summary["tokens"].obj()["dmn"].obj()).containsEntry("input", 2000)
        assertThat(summary["tokens"].obj()["tests"].obj()).containsEntry("input", 900)
    }

    @Test
    fun `a session stops after cycles ERROR cycles and reports the budget as exhausted`() {
        val down = RuntimeException("UnresolvedModelServerException")
        val tracks = ScriptedTracks(listOf(down, down), listOf(suite, suite))

        val log = runner(tracks).run(config(cycles = 2), dir)

        val events = events(log)
        assertThat(events.filter { it["event"] == "cycle_finished" }.map { it["outcome"] }).containsExactly("ERROR", "ERROR")
        assertThat(events.last()).containsEntry("validCycles", 0).containsEntry("errorCycles", 2).containsEntry("budgetExhausted", true)
    }

    @Test
    fun `a rerun keeps earlier ERROR cycles and continues with the next cycle number`() {
        runner(ScriptedTracks(listOf(RuntimeException("UnresolvedModelServerException")), listOf(suite))).run(config(cycles = 1), dir)
        val retry = ScriptedTracks(listOf(goodDmn), listOf(suite))

        val log = runner(retry).run(config(cycles = 1), dir)

        val events = events(log)
        assertThat(events.filter { it["event"] == "run_started" }[1]).containsEntry("skippedCycles", listOf(1))
        val finished = events.filter { it["event"] == "cycle_finished" }
        assertThat(finished.map { it["cycle"] }).containsExactly(1, 2)
        assertThat(finished.map { it["outcome"] }).containsExactly("ERROR", "ACCEPTED")
        assertThat(events.last()).containsEntry("validCycles", 1).containsEntry("errorCycles", 1).containsEntry("budgetExhausted", false)
        assertThat(events.last()["outcomes"]).isEqualTo(mapOf("ERROR" to 1, "ACCEPTED" to 1))
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
    fun `top-level rag is rejected in favour of dmn rag`() {
        assertThatThrownBy { config().copy(rag = RagSpec(enabled = true)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("dmn.rag")
    }

    @Test
    fun `RAG on the test track is rejected in the run config`() {
        assertThatThrownBy { config().copy(tests = TrackSpec(model = "local:x", rag = TrackRagSpec(enabled = true, embeddingModel = "local:e"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tests.rag")
    }

    @Test
    fun `dmn rag needs an embedding model`() {
        assertThatThrownBy { TrackSpec(model = "anthropic:x", rag = TrackRagSpec(enabled = true)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("embeddingModel")
    }

    @Test
    fun `with dmn rag the run logs the retriever and every attempt logs the segments it got`() {
        val ragConfig = config(cycles = 1).copy(
            dmn = TrackSpec(model = "anthropic:priced-model", rag = TrackRagSpec(enabled = true, embeddingModel = "local:bge-m3", k = 4, minScore = 0.6)),
        )

        val events = events(runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(ragConfig, dir))

        val rag = events[0]["rag"].obj()
        assertThat(rag["dmn"].obj()).containsEntry("embeddingModel", "local:bge-m3").containsEntry("corpusHash", "abc").containsEntry("k", 4)
        assertThat(rag["tests"]).isNull()
        val dmnTrack = events.single { it["event"] == "attempt" }["tracks"].obj()["dmn"].obj()
        assertThat(dmnTrack["rag"].obj()).containsEntry("count", 1)
        assertThat((dmnTrack["rag"].obj()["segments"] as List<*>).single().obj()).containsEntry("id", "DMN.txt#7").containsEntry("score", 0.83)
    }

    @Test
    fun `without rag the attempt says so explicitly`() {
        val events = events(runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(config(cycles = 1), dir))

        assertThat(events[0]["rag"].obj()).containsEntry("dmn", null).containsEntry("tests", null)
        assertThat(events.single { it["event"] == "attempt" }["tracks"].obj()["dmn"].obj()).containsEntry("rag", null)
    }

    @Test
    fun `run config reads the documented JSON and rejects unknown fields`() {
        val json = """{"runId": "r1", "ruleSet": "mortgage", "version": "ambiguous", "language": "en",
            "dmn": {"model": "anthropic:claude-sonnet-5-5", "maxTokens": 16000, "rag": {"enabled": true, "embeddingModel": "local:bge-m3", "k": 6, "minScore": 0.7}},
            "tests": {"model": "local:gemma4:26b", "temperature": 0.7, "seed": 42},
            "validator": "STRICT", "strategy": "ESCALATE_ON_STABLE_DISAGREEMENT", "retryBudget": 3, "conflictMemory": true, "rag": {"enabled": false}, "cycles": 3}"""
        val file = dir.resolve("r1.json").also { Files.writeString(it, json) }

        val config = RunConfig.read(file)

        assertThat(config.tests!!.temperature).isEqualTo(0.7)
        assertThat(config.dmn!!.temperature).isNull()
        assertThat(config.strategy).isEqualTo(RepairStrategy.ESCALATE_ON_STABLE_DISAGREEMENT)
        assertThat(config.conflictMemory).isTrue()
        assertThat(config.dmn!!.rag.enabled).isTrue()
        assertThat(config.dmn!!.toTrackConfig().rag!!.embeddingModel).isEqualTo("local:bge-m3")
        assertThat(config.tests!!.toTrackConfig().rag).isNull()
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

    @Test
    fun `an unparseable oracle answer is logged in its attempt, with its own seed and cost, and does not use up the attempt`() {
        val events = events(runner(ScriptedTracks(listOf(goodDmn), listOf("not json", suite))).run(config(cycles = 1), dir))

        val attempt = events.single { it["event"] == "attempt" }
        val failure = (attempt["testParseFailures"] as List<*>).single().obj()
        assertThat(failure).containsEntry("raw", "not json").containsEntry("seedOffset", 1001)
        assertThat(failure["track"].obj()).containsEntry("seed", 42 + 1001).containsEntry("inputTokens", 300)
        assertThat(attempt["tracks"].obj()["tests"].obj()).containsEntry("seed", 42 + 1101)
        val finished = events.single { it["event"] == "cycle_finished" }
        assertThat(finished).containsEntry("outcome", "ACCEPTED").containsEntry("attempts", 1)
        assertThat(finished["tokens"].obj()["tests"].obj()).containsEntry("calls", 2)
    }

    // --- ambiguity flags and the literal ground truth (arm A1, REVISION_PLAN step 16) ---

    private val note = "rules 1 and 2 both apply"
    private val flaggedSuite = """[{"object": ${app(true, 650)}, "expected": [{"product": "Fixed30"}]},
                                   {"object": ${app(false, 720)}, "expected": [{"product": "Fixed30"}], "ambiguous": true, "ambiguityNote": "$note"}]"""

    @Test
    fun `A1 logs flagged cases per attempt, and the cycle its flags and the source of the escalation`() {
        val a1 = config(cycles = 1, strategy = RepairStrategy.ESCALATE_ON_STABLE_DISAGREEMENT, conflictMemory = true, escalateOnAmbiguityFlags = true)

        val log = runner(ScriptedTracks(listOf(goodDmn), listOf(flaggedSuite))).run(a1, dir)

        val events = events(log)
        assertThat(events.first()["config"].obj()).containsEntry("escalateOnAmbiguityFlags", true)
        val expectedFlag = mapOf(
            "source" to "SUITE", "index" to 1,
            "input" to mapOf("application" to mapOf("fixedPreference" to false, "creditScore" to 720.0)),
            "expected" to listOf(mapOf("product" to "Fixed30")), "note" to note,
        )
        assertThat(events.single { it["event"] == "attempt" }["flaggedCases"]).isEqualTo(listOf(expectedFlag))
        val finished = events.single { it["event"] == "cycle_finished" }
        assertThat(finished).containsEntry("outcome", "AMBIGUOUS").containsEntry("ambiguitySource", "FLAGS")
        assertThat(finished["ambiguityFlags"].obj()).containsEntry("count", 1).containsEntry("total", 1)
            .containsEntry("cases", listOf(expectedFlag))
        assertThat(events.last()["outcomes"]).isEqualTo(mapOf("AMBIGUOUS" to 1))
    }

    @Test
    fun `an accepted cycle without flags has no ambiguity source`() {
        val log = runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(config(cycles = 1, escalateOnAmbiguityFlags = true), dir)

        val finished = events(log).single { it["event"] == "cycle_finished" }
        assertThat(finished).containsEntry("outcome", "ACCEPTED").containsEntry("ambiguitySource", null)
        assertThat(finished["ambiguityFlags"].obj()).containsEntry("count", 0).containsEntry("total", 0)
    }

    @Test
    fun `with a literal ground truth the final DMN is also evaluated against it, as groundTruthAlt`() {
        val literalSuite = """[{"object": ${app(true, 650)}, "expected": [{"product": "Fixed30"}]}]"""
        val tracks = ScriptedTracks(listOf(goodDmn, firstNoDefault), listOf(suite, literalSuite))

        val log = runner(tracks).run(config(cycles = 2, version = "literal"), dir)

        val events = events(log)
        assertThat(events.first()["ruleSet"].obj()).containsEntry("groundTruthAltSize", 3)
        val finished = events.filter { it["event"] == "cycle_finished" }
        assertThat(finished.map { it["groundTruth"].obj()["accepted"] }).containsExactly(true, false)
        assertThat(finished.map { it["groundTruthAlt"].obj()["accepted"] }).containsExactly(false, true)
        assertThat(finished[0]["groundTruthAlt"].obj()).containsEntry("file", "ground-truth.literal.json")
        assertThat(finished[0]["groundTruthAlt"].obj()["counts"]).isEqualTo(mapOf("PASS" to 1, "MISMATCH" to 1, "UNEXPECTED_RESULT" to 1))
        assertThat(events.last()["groundTruth"].obj())
            .containsEntry("acceptedCorrect", 1).containsEntry("acceptedWrong", 1)
            .containsEntry("acceptedCorrectAlt", 1).containsEntry("acceptedWrongAlt", 1)
    }

    @Test
    fun `without a literal ground truth groundTruthAlt is null`() {
        val log = runner(ScriptedTracks(listOf(goodDmn), listOf(suite))).run(config(cycles = 1), dir)

        val events = events(log)
        assertThat(events.single { it["event"] == "cycle_finished" }).containsEntry("groundTruthAlt", null)
        assertThat(events.last()["groundTruth"].obj()).containsEntry("acceptedCorrectAlt", null).containsEntry("acceptedWrongAlt", null)
    }
}
