package com.grimels.keristra.core.context

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DomainRegistryTest {

    private val registry = DomainRegistry()

    @Test
    fun `reads a domain schema as a classpath resource`() {
        assertThat(registry.schema("mortgage")).contains("\"creditScore\"")
    }

    @Test
    fun `knows the V0 domains`() {
        assertThat(registry.domains).contains("mortgage", "education", "vap", "bmi", "hotels", "users")
    }

    @Test
    fun `an unknown domain is an error, not a silent fallback to the user schema as in V0`() {
        assertThatThrownBy { registry.schema("fop") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("fop")
            .hasMessageContaining("mortgage")
    }

    @Test
    fun `schema paths come from JSON schemas only`() {
        assertThat(registry.schemaPaths("mortgage")).contains("application.creditScore", "application.ltv")
        assertThat(registry.schemaPaths("bmi")).isNull()
    }

    @Test
    fun `extra domains can be registered by resource path`() {
        val extended = DomainRegistry(mapOf("toy" to "schema/mortgage-schema.json"))

        assertThat(extended.schema("toy")).contains("\"creditScore\"")
    }
}
