package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.grimels.keristra.core.generation.GenerationSettings
import com.grimels.keristra.core.generation.RagSettings
import com.grimels.keristra.core.generation.TrackConfig
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
 * Only `dmn.rag` may be enabled. `minScore` is a LangChain4J relevance score, `(cosine + 1) / 2`.
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
    val dmn: TrackSpec,
    val tests: TrackSpec,
    val validator: ValidatorMode = ValidatorMode.STRICT,
    val strategy: RepairStrategy = RepairStrategy.PAIRED_REGEN,
    val retryBudget: Int = 3,
    val stableAttempts: Int = 2,
    /** REVISION_PLAN step 10; off keeps the earlier behaviour (both arms are needed for the article). */
    val conflictMemory: Boolean = false,
    val rag: RagSpec = RagSpec(),
    val cycles: Int,
) {
    init {
        require(runId.matches(Regex("[A-Za-z0-9._-]+"))) { "runId must be a plain file name, got '$runId'" }
        require(cycles >= 1) { "cycles must be at least 1" }
        require(!rag.enabled) { "Top-level rag is replaced by dmn.rag (REVISION_PLAN step 14)" }
        require(!tests.rag.enabled) { "tests.rag must stay off: RAG is only for the DMN track (REVISION_PLAN step 14)" }
    }

    companion object {
        fun read(file: Path): RunConfig = jacksonObjectMapper().readValue(Files.readString(file))
    }
}
