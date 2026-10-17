package com.grimels.keristra.core.context

import com.grimels.keristra.core.generation.DomainContextProvider
import com.grimels.keristra.core.validation.SchemaPaths
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Domain id -> schema (REVISION_PLAN 4.3, replaces the `if` chains of F12 for new code). Schemas are read as
 * classpath resources, so they load from the packaged jar too (V0's SchemaResolver used File and failed there).
 * Unknown domains are an error; V0 silently fell back to the user schema.
 */
class DomainRegistry(extraSchemas: Map<String, String> = emptyMap()) : DomainContextProvider {

    private val schemaResources = V0_SCHEMAS + extraSchemas

    val domains: Set<String> get() = schemaResources.keys

    override fun schema(domain: String): String {
        val resource = schemaResources[domain]
            ?: throw IllegalArgumentException("Unknown domain '$domain'; known: ${domains.sorted()}")
        return DomainRegistry::class.java.getResourceAsStream("/$resource")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Schema resource missing for domain '$domain': $resource")
    }

    /** Property paths for the strict validator's SCHEMA_ERROR check; null for XML schemas. */
    fun schemaPaths(domain: String): Set<String>? =
        if (schemaResources[domain]?.endsWith(".json") == true) SchemaPaths.fromJsonSchema(schema(domain)) else null

    companion object {
        val V0_SCHEMAS = mapOf(
            "mortgage" to "schema/mortgage-schema.json",
            "education" to "schema/education-schema.json",
            "vap" to "schema/vap-schema.xml",
            "bmi" to "schema/bmi-schema.xml",
            "hotels" to "schema/hotel-schema.xml",
            "users" to "schema/user-schema.xml",
        )
    }
}

@Configuration
class DomainConfig {
    @Bean
    fun domainRegistry() = DomainRegistry()
}
