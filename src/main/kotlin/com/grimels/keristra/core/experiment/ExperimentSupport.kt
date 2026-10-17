package com.grimels.keristra.core.experiment

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.BufferedWriter
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

/** `keristra.pricing.*`: USD per million tokens by model id; providers in [freeProviders] (local models) cost 0. */
@ConfigurationProperties("keristra.pricing")
data class PricingProperties(
    val models: Map<String, ModelPrice> = emptyMap(),
    val freeProviders: Set<String> = emptySet(),
) {
    data class ModelPrice(val inputPerMtok: Double, val outputPerMtok: Double)

    /** Null when the model has no configured price or the provider did not report token usage. */
    fun costUsd(spec: String, inputTokens: Int?, outputTokens: Int?): Double? {
        if (spec.substringBefore(':') in freeProviders) return 0.0
        val price = models[spec.substringAfter(':')] ?: return null
        if (inputTokens == null || outputTokens == null) return null
        return (inputTokens * price.inputPerMtok + outputTokens * price.outputPerMtok) / 1_000_000
    }
}

@Configuration
@EnableConfigurationProperties(PricingProperties::class)
class ExperimentConfig

fun interface GitInfo {
    /** Commit id of the working tree, suffixed `-dirty` when tracked files are modified; null outside a git checkout. */
    fun commit(): String?
}

@Component
class ProcessGitInfo : GitInfo {
    override fun commit(): String? {
        return try {
            val head = git("rev-parse", "HEAD") ?: return null
            if (git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) head else "$head-dirty"
        } catch (e: Exception) {
            null
        }
    }

    private fun git(vararg args: String): String? {
        val process = ProcessBuilder("git", *args).redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().readText().trim()
    }
}

/** One JSON object per line; every line is flushed, so a crash loses at most the cycle in progress. */
internal object JsonlLog {

    fun read(file: Path, mapper: ObjectMapper): List<Map<String, Any?>> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).filter { it.isNotBlank() }.mapNotNull { line ->
            // A line cut short by a crash is skipped; its cycle has no cycle_finished and is rerun.
            runCatching { mapper.readValue<Map<String, Any?>>(line) }.getOrNull()
        }
    }

    class Writer(file: Path, private val mapper: ObjectMapper) : Closeable {
        private val out: BufferedWriter = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

        fun append(event: Map<String, Any?>) {
            out.write(mapper.writeValueAsString(event))
            out.newLine()
            out.flush()
        }

        override fun close() = out.close()
    }
}
