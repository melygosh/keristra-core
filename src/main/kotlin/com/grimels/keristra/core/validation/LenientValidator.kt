package com.grimels.keristra.core.validation

import org.camunda.bpm.dmn.engine.DmnEngine
import org.springframework.stereotype.Component

/**
 * Replica of the V0 verdict (`DmnValidator.isValidDMN` + `DmnValidationResult.isValid`), bugs included,
 * so V0 numbers can be reproduced on the new harness:
 * - F1: an empty result is never compared, so the case passes whatever was expected;
 * - an unparseable table yields no results, which V0 treats as "no errors" -> accepted;
 * - when V0's comparison throws (e.g. `expected: null`, or no `application.id` for the message),
 *   the whole attempt failed in V0, so the table is not accepted.
 * Schema paths are ignored: V0 had no schema check.
 */
@Component
class LenientValidator(private val dmnEngine: DmnEngine) : Validator {

    override val mode = ValidatorMode.LENIENT

    override fun validate(dmnXml: String, testCases: List<Map<String, Any?>>, schemaPaths: Set<String>?): ValidationReport {
        val table = try {
            DmnTable.parse(dmnEngine, dmnXml)
        } catch (e: Exception) {
            return ValidationReport(mode, accepted = true, tableError = TableError.PARSE_ERROR, tableErrorMessage = e.message ?: e.javaClass.name)
        }

        val cases = testCases.mapIndexed { index, testCase ->
            val v0 = try {
                DmnValidationResult(testCase, table.evaluate(dmnEngine, testCase))
            } catch (e: Exception) {
                DmnValidationResult(testCase, null).apply { setErrorMessage(e.message ?: "Unknown error") }
            }
            val actual = v0.result?.resultList?.map { it.toMap() }.orEmpty()
            try {
                when (val message = v0.isValid()) {
                    null -> CaseResult(index, CaseOutcome.PASS, actual)
                    else -> CaseResult(index, if (v0.result == null) CaseOutcome.EVAL_ERROR else CaseOutcome.MISMATCH, actual, message = message)
                }
            } catch (e: Exception) {
                CaseResult(index, CaseOutcome.MISMATCH, actual, message = "V0 comparison threw: $e")
            }
        }
        return ValidationReport(mode, accepted = cases.all { it.outcome == CaseOutcome.PASS }, cases = cases)
    }
}
