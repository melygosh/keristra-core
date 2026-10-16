package com.grimels.keristra.core.validation

import com.grimels.keristra.core.validation.CaseOutcome.EVAL_ERROR
import com.grimels.keristra.core.validation.CaseOutcome.MISMATCH
import com.grimels.keristra.core.validation.CaseOutcome.PASS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** LenientValidator must reproduce V0 (`DmnValidator.isValidDMN`) verdicts exactly, bugs included. */
class LenientValidatorTest {

    private val validator = LenientValidator(testEngine)

    private fun mortgageCase(id: Int, fixedPreference: Boolean, creditScore: Int, expected: String) =
        """{"object": {"application": {"id": $id, "fixedPreference": $fixedPreference, "creditScore": $creditScore}}, "expected": $expected}"""

    @Test
    fun `F1 - no rule matches but a product is expected - V0 accepts it`() {
        val report = validator.validate(firstNoDefault, cases("[${mortgageCase(1, false, 650, """[{"product": "Fixed30"}]""")}]"))

        assertThat(report.cases.single().outcome).isEqualTo(PASS)
        assertThat(report.accepted).isTrue()
    }

    @Test
    fun `mismatch is reported with the V0 message`() {
        val report = validator.validate(firstNoDefault, cases("[${mortgageCase(7, false, 720, """[{"product": "Fixed30"}]""")}]"))

        val result = report.cases.single()
        assertThat(result.outcome).isEqualTo(MISMATCH)
        // Gson parses the id as a Double, hence "7.0" -- the same format as in the 2025-10-31 logs.
        assertThat(result.message).isEqualTo("[7.0] Mismatch on key 'product': expected 'Fixed30', got 'ARM7/1'")
        assertThat(report.accepted).isFalse()
    }

    @Test
    fun `V0 accepts a table that cannot be parsed`() {
        val report = validator.validate("<definitions><broken", cases("[${mortgageCase(1, true, 650, """[{"product": "Fixed30"}]""")}]"))

        assertThat(report.tableError).isEqualTo(TableError.PARSE_ERROR)
        assertThat(report.accepted).isTrue()
    }

    @Test
    fun `V0 rejects the attempt when its comparison throws (expected null with a result)`() {
        val report = validator.validate(firstNoDefault, cases("[${mortgageCase(1, false, 720, "null")}]"))

        assertThat(report.cases.single().outcome).isEqualTo(MISMATCH)
        assertThat(report.accepted).isFalse()
    }

    @Test
    fun `engine errors are EVAL_ERROR and rejected`() {
        val report = validator.validate(firstNoDefault, cases("""[{"expected": [{"product": "Fixed30"}]}]"""))

        assertThat(report.cases.single().outcome).isEqualTo(EVAL_ERROR)
        assertThat(report.accepted).isFalse()
    }
}
