package com.grimels.keristra.core.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchemaPathsTest {

    @Test
    fun `collects nested property paths from a JSON schema`() {
        val schema = """
            {"type": "object", "properties": {
              "application": {"type": "object", "properties": {
                "creditScore": {"type": "integer"},
                "applicant": {"type": "object", "properties": {"age": {"type": "integer"}}},
                "debts": {"type": ["array", "null"], "items": {"type": "object", "properties": {"amount": {"type": "number"}}}}
              }}
            }}
        """

        assertThat(SchemaPaths.fromJsonSchema(schema)).containsExactlyInAnyOrder(
            "application", "application.creditScore", "application.applicant", "application.applicant.age",
            "application.debts", "application.debts.amount",
        )
    }

    @Test
    fun `mortgage schema exposes the paths used by generated tables`() {
        val schema = javaClass.getResource("/schema/mortgage-schema.json")!!.readText()

        assertThat(SchemaPaths.fromJsonSchema(schema)).contains("application.creditScore", "application.fixedPreference")
    }
}
