package com.grimels.keristra.core.experiment

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import org.springframework.stereotype.Component

/**
 * One rule-set version on disk (REVISION_PLAN 4.8): `resources/rulesets/<name>/<version>/` with `rules.<lang>.txt`,
 * `meta.json` (must name the `domain`), `ground-truth.json` (validator format; `expected: null` = no rule applies)
 * and optionally `reference.dmn`. [hash] covers exactly the files this load used.
 */
data class RuleSet(
    val id: String,
    val domain: String,
    val version: String,
    val language: String,
    val rules: String,
    val meta: Map<String, Any?>,
    val groundTruth: List<Map<String, Any?>>,
    val hash: String,
)

@Component
class RuleSetRepository {

    fun load(ruleSet: String, version: String, language: String): RuleSet {
        val dir = "rulesets/$ruleSet/$version"
        val files = linkedMapOf(
            "rules.$language.txt" to read("$dir/rules.$language.txt", required = true),
            "meta.json" to read("$dir/meta.json", required = true),
            "ground-truth.json" to read("$dir/ground-truth.json", required = true),
            "reference.dmn" to read("$dir/reference.dmn", required = false),
        ).filterValues { it != null }.mapValues { it.value!! }

        val meta: Map<String, Any?> = GSON.fromJson(files.getValue("meta.json").toString(Charsets.UTF_8), MAP_TYPE)
        val groundTruth: List<Map<String, Any?>> = GSON.fromJson(files.getValue("ground-truth.json").toString(Charsets.UTF_8), LIST_TYPE)
        val domain = meta["domain"] as? String ?: throw IllegalArgumentException("$dir/meta.json must name the domain")

        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (name, bytes) -> digest.update("$name\n".toByteArray()); digest.update(bytes); digest.update("\n".toByteArray()) }

        return RuleSet(
            id = "$ruleSet/$version",
            domain = domain,
            version = version,
            language = language,
            rules = files.getValue("rules.$language.txt").toString(Charsets.UTF_8).trim(),
            meta = meta,
            groundTruth = groundTruth,
            hash = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun read(path: String, required: Boolean): ByteArray? =
        RuleSetRepository::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
            ?: if (required) throw IllegalArgumentException("Rule-set file not found on the classpath: $path") else null

    private companion object {
        val GSON = Gson()
        val MAP_TYPE = object : TypeToken<Map<String, Any?>>() {}.type
        val LIST_TYPE = object : TypeToken<List<Map<String, Any?>>>() {}.type
    }
}
