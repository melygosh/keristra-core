package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.RagSettings
import com.grimels.keristra.core.generation.TrackConfig
import com.grimels.keristra.core.mutation.MutationOperator
import com.grimels.keristra.core.orchestration.RepairStrategy
import com.grimels.keristra.core.validation.ValidatorMode
import java.nio.file.Files
import java.nio.file.Path

/**
 * One track of a run. Sampling is per track because providers differ (REVISION_PLAN F16): current Claude models
 * reject `temperature`, local models take it. `seed` is the base; each attempt adds `cycle * 1000 + attempt`.
 */
data class TrackSpec(
    val model: String,
    val prompt: String = "v1",
    val temperature: Double? = null,
    val seed: Long? = null,
    val maxTokens: Int = 16000,
    val rag: TrackRagSpec = TrackRagSpec(),
) {
    init {
        require(!rag.enabled || !rag.embeddingModel.isNullOrBlank()) {
            "rag.enabled needs rag.embeddingModel, an OpenAI-compatible embeddings model such as local:bge-m3"
        }
    }

    fun toTrackConfig() = TrackConfig(
        model, prompt, GenerationSettings(temperature, seed, maxTokens),
        rag = if (rag.enabled) {
            RagSettings(rag.embeddingModel!!, rag.corpus.orEmpty(), rag.k, rag.minScore, rag.segmentSize, rag.segmentOverlap)
        } else null,
    )
}

/**
 * RAG for one track (REVISION_PLAN step 14): local embeddings over a frozen corpus (default: the V0 corpus).
 * Only `dmn.rag` may be enabled. `minScore` is a LangChain4J relevance score, `(cosine + 1) / 2`; calibrated value for
 * `local:bge-m3` on the V0 corpus: 0.72 (REVISION_PLAN step 14). The default 0.0 means plain top-k.
 */
data class TrackRagSpec(
    val enabled: Boolean = false,
    val embeddingModel: String? = null,
    val corpus: List<String>? = null,
    val k: Int = 6,
    val minScore: Double = 0.0,
    val segmentSize: Int = 1024,
    val segmentOverlap: Int = 128,
)

/** Kept so older configs still read; RAG is configured per track now (`dmn.rag`). */
data class RagSpec(val enabled: Boolean = false)

/** `experiments/<name>.json` (REVISION_PLAN 4.6). Unknown fields are rejected, so a typo cannot silently fall back to a default. */
data class RunConfig(
    val runId: String,
    val ruleSet: String,
    val version: String,
    val language: String,
    /** Absent in suite-only runs. */
    val dmn: TrackSpec? = null,
    /** Absent in suite-only runs that evaluate fixed suites only. */
    val tests: TrackSpec? = null,
    /** Default: SUITES when there is no `dmn`, CYCLES otherwise. */
    val mode: RunMode? = null,
    val validator: ValidatorMode = ValidatorMode.STRICT,
    val strategy: RepairStrategy = RepairStrategy.PAIRED_REGEN,
    val retryBudget: Int = 3,
    val stableAttempts: Int = 2,
    /** REVISION_PLAN step 10; off keeps the earlier behaviour (both arms are needed for the article). */
    val conflictMemory: Boolean = false,
    /** Arm A1 (REVISION_PLAN step 16): an otherwise accepted attempt with cases the oracle flagged ends AMBIGUOUS. */
    val escalateOnAmbiguityFlags: Boolean = false,
    val rag: RagSpec = RagSpec(),
    /** Number of cycles (CYCLES) or of generated suites (SUITES). */
    val cycles: Int,
    val fixedSuites: List<FixedSuiteSpec> = emptyList(),
    val evaluate: List<EvaluateSource> = emptyList(),
) {
    @get:JsonIgnore
    val effectiveMode: RunMode get() = mode ?: if (dmn == null) RunMode.SUITES else RunMode.CYCLES

    init {
        require(runId.matches(Regex("[A-Za-z0-9._-]+"))) { "runId must be a plain file name, got '$runId'" }
        require(!rag.enabled) { "Top-level rag is replaced by dmn.rag (REVISION_PLAN step 14)" }
        require(tests?.rag?.enabled != true) { "tests.rag must stay off: RAG is only for the DMN track (REVISION_PLAN step 14)" }
        when (effectiveMode) {
            RunMode.CYCLES -> {
                require(dmn != null && tests != null) { "Cycle runs need both a dmn and a tests track; leave out dmn for a suite-only run" }
                require(cycles >= 1) { "cycles must be at least 1" }
            }
            RunMode.SUITES -> {
                require(cycles >= 0) { "cycles must not be negative" }
                require(cycles == 0 || tests != null) { "Generating $cycles suites needs a tests track" }
                require(cycles > 0 || fixedSuites.isNotEmpty()) { "A suite-only run needs generated (cycles > 0) or fixed suites" }
            }
        }
    }

    companion object {
        fun read(file: Path): RunConfig = jacksonObjectMapper().readValue(Files.readString(file))
    }
}

enum class RunMode { CYCLES, SUITES }

/** A human ground truth used as one fixed test suite (E1-H45, E1-H8): `<ruleSet>/<version>`, optionally one category. */
data class FixedSuiteSpec(val groundTruth: String, val category: String? = null, val name: String? = null)

/**
 * A DMN source to evaluate every suite against (REVISION_PLAN step 15): exactly one of a rule-set reference
 * (`<ruleSet>/<version>`), the mutants of a reference (optionally limited to `operators`), or a DMN file on disk.
 * `equivalentMutants` lists mutant ids excluded from the second mutation score.
 */
data class EvaluateSource(
    val reference: String? = null,
    val mutantsOf: String? = null,
    val file: String? = null,
    val operators: List<MutationOperator>? = null,
    val equivalentMutants: List<String> = emptyList(),
) {
    init {
        require(listOfNotNull(reference, mutantsOf, file).size == 1) { "An evaluate source needs exactly one of reference, mutantsOf, file" }
        require(mutantsOf != null || (operators == null && equivalentMutants.isEmpty())) { "operators and equivalentMutants apply to mutantsOf only" }
    }
}
