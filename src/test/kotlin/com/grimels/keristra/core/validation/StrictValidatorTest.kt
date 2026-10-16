package com.grimels.keristra.core.validation

import com.grimels.keristra.core.validation.CaseOutcome.EMPTY_RESULT
import com.grimels.keristra.core.validation.CaseOutcome.EVAL_ERROR
import com.grimels.keristra.core.validation.CaseOutcome.MISMATCH
import com.grimels.keristra.core.validation.CaseOutcome.MULTI_RESULT
import com.grimels.keristra.core.validation.CaseOutcome.PASS
import com.grimels.keristra.core.validation.CaseOutcome.UNEXPECTED_RESULT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrictValidatorTest {

    private val validator = StrictValidator(testEngine)

    private fun case(obj: String, expected: String) = """{"object": $obj, "expected": $expected}"""

    @Test
    fun `PASS when the result matches every expected key`() {
        val report = validator.validate(firstNoDefault, cases("[${case(app(true, 650), """[{"product": "Fixed30"}]""")}]"))

        assertThat(report.cases.map { it.outcome }).containsExactly(PASS)
        assertThat(report.accepted).isTrue()
    }

    @Test
    fun `F1 - no rule matches but a product is expected - EMPTY_RESULT and rejected`() {
        val report = validator.validate(firstNoDefault, cases("[${case(app(false, 650), """[{"product": "Fixed30"}]""")}]"))

        assertThat(report.cases.single().outcome).isEqualTo(EMPTY_RESULT)
        assertThat(report.accepted).isFalse()
    }

    @Test
    fun `MISMATCH records the differing key with expected and actual`() {
        val report = validator.validate(firstNoDefault, cases("[${case(app(false, 720), """[{"product": "Fixed30"}]""")}]"))

        val result = report.cases.single()
        assertThat(result.outcome).isEqualTo(MISMATCH)
        assertThat(result.mismatches).containsExactly(KeyMismatch(row = 0, key = "product", expected = "Fixed30", actual = "ARM7/1"))
        assertThat(report.accepted).isFalse()
    }

    @Test
    fun `UNEXPECTED_RESULT when the case expects no rule to apply but one does`() {
        val report = validator.validate(
            firstNoDefault,
            cases("[${case(app(false, 720), "[]")}, ${case(app(false, 720), "null")}, ${case(app(false, 720), """[{"product": null}]""")}]"),
        )

        assertThat(report.cases.map { it.outcome }).containsExactly(UNEXPECTED_RESULT, UNEXPECTED_RESULT, UNEXPECTED_RESULT)
    }

    @Test
    fun `PASS when the case expects no rule to apply and none does`() {
        val report = validator.validate(firstNoDefault, cases("[${case(app(false, 650), "[]")}]"))

        assertThat(report.cases.single().outcome).isEqualTo(PASS)
        assertThat(report.accepted).isTrue()
    }

    @Test
    fun `MULTI_RESULT when overlapping rules match under UNIQUE`() {
        val unique = dmn(
            "UNIQUE",
            listOf(In("application.creditScore", "double")),
            Out("product"),
            listOf(listOf("&gt;= 700") to "\"A\"", listOf("&gt;= 600") to "\"B\""),
        )

        val report = validator.validate(unique, cases("""[{"object": {"application": {"creditScore": 720}}, "expected": [{"product": "A"}]}]"""))

        assertThat(report.cases.single().outcome).isEqualTo(MULTI_RESULT)
        assertThat(report.accepted).isFalse()
    }

    @Test
    fun `EVAL_ERROR when the engine cannot evaluate the input`() {
        val typed = dmn(
            "FIRST",
            listOf(In("application.creditScore", "integer")),
            Out("product"),
            listOf(listOf("&gt;= 700") to "\"A\""),
        )

        val report = validator.validate(typed, cases("""[{"object": {"application": {"creditScore": "not a number"}}, "expected": [{"product": "A"}]}]"""))

        val result = report.cases.single()
        assertThat(result.outcome).isEqualTo(EVAL_ERROR)
        assertThat(result.message).isNotBlank()
        assertThat(report.accepted).isFalse()
    }

    @Test
    fun `RULE ORDER compares every expected row and flags a missing row as MISMATCH`() {
        val ruleOrder = dmn(
            "RULE ORDER",
            listOf(In("application.fixedPreference", "boolean")),
            Out("fee"),
            listOf(listOf("") to "\"base\"", listOf("true") to "\"fixed-fee\""),
        )
        val obj = """{"application": {"fixedPreference": true}}"""

        val report = validator.validate(
            ruleOrder,
            cases(
                """[{"object": $obj, "expected": [{"fee": "base"}, {"fee": "fixed-fee"}]},
                    {"object": $obj, "expected": [{"fee": "base"}]}]""",
            ),
        )

        assertThat(report.cases.map { it.outcome }).containsExactly(PASS, MISMATCH)
        assertThat(report.cases[1].mismatches).containsExactly(KeyMismatch(row = -1, key = "#rows", expected = 1, actual = 2))
    }

    @Test
    fun `numbers from JSON compare equal to engine integers`() {
        val numeric = dmn(
            "FIRST",
            listOf(In("application.creditScore", "double")),
            Out("rate", "integer"),
            listOf(listOf("&gt;= 700") to "3"),
        )

        val report = validator.validate(numeric, cases("""[{"object": {"application": {"creditScore": 720}}, "expected": [{"rate": 3}]}]"""))

        assertThat(report.cases.single().outcome).isEqualTo(PASS)
    }

    @Test
    fun `PARSE_ERROR for malformed XML and for a model without decision id 'decision'`() {
        val testCases = cases("[${case(app(true, 650), """[{"product": "Fixed30"}]""")}]")

        val malformed = validator.validate("<definitions><broken", testCases)
        val wrongId = validator.validate(firstNoDefault.replace("decision id=\"decision\"", "decision id=\"other\""), testCases)

        for (report in listOf(malformed, wrongId)) {
            assertThat(report.tableError).isEqualTo(TableError.PARSE_ERROR)
            assertThat(report.tableErrorMessage).isNotBlank()
            assertThat(report.cases).isEmpty()
            assertThat(report.accepted).isFalse()
        }
    }

    @Test
    fun `SCHEMA_ERROR when an input expression references a path absent from the schema`() {
        val schemaPaths = setOf("application", "application.fixedPreference")

        val report = validator.validate(firstNoDefault, cases("[${case(app(true, 650), """[{"product": "Fixed30"}]""")}]"), schemaPaths)

        assertThat(report.tableError).isEqualTo(TableError.SCHEMA_ERROR)
        assertThat(report.tableErrorMessage).contains("application.creditScore")
        assertThat(report.cases.map { it.outcome }).containsExactly(PASS)
        assertThat(report.accepted).isFalse()
    }

    @Test
    fun `no SCHEMA_ERROR when every referenced path exists`() {
        val schemaPaths = setOf("application", "application.fixedPreference", "application.creditScore")

        val report = validator.validate(firstNoDefault, cases("[${case(app(true, 650), """[{"product": "Fixed30"}]""")}]"), schemaPaths)

        assertThat(report.tableError).isNull()
        assertThat(report.accepted).isTrue()
    }

    @Test
    fun `report contains every outcome, not only the first failure`() {
        val report = validator.validate(
            firstNoDefault,
            cases(
                """[${case(app(true, 650), """[{"product": "Fixed30"}]""")},
                    ${case(app(false, 650), """[{"product": "Fixed30"}]""")},
                    ${case(app(false, 720), """[{"product": "Fixed30"}]""")},
                    ${case(app(false, 720), "[]")},
                    ${case(app(false, 650), "[]")}]""",
            ),
        )

        assertThat(report.cases.map { it.outcome }).containsExactly(PASS, EMPTY_RESULT, MISMATCH, UNEXPECTED_RESULT, PASS)
        assertThat(report.cases.map { it.index }).containsExactly(0, 1, 2, 3, 4)
        assertThat(report.counts).containsEntry(PASS, 2).containsEntry(EMPTY_RESULT, 1)
            .containsEntry(MISMATCH, 1).containsEntry(UNEXPECTED_RESULT, 1)
        assertThat(report.accepted).isFalse()
    }
}
