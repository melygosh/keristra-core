package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.grimels.keristra.core.generation.GenerationSettings
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
) {
    fun toTrackConfig() = TrackConfig(model, prompt, GenerationSettings(temperature, seed, maxTokens))
}

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
    val rag: RagSpec = RagSpec(),
    val cycles: Int,
) {
    init {
        require(runId.matches(Regex("[A-Za-z0-9._-]+"))) { "runId must be a plain file name, got '$runId'" }
        require(cycles >= 1) { "cycles must be at least 1" }
    }

    companion object {
        fun read(file: Path): RunConfig = jacksonObjectMapper().readValue(Files.readString(file))
    }
}
