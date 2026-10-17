package com.grimels.keristra.core.validation

import org.camunda.bpm.dmn.engine.DmnEngine
import org.camunda.bpm.dmn.engine.impl.hitpolicy.DmnHitPolicyException
import org.camunda.bpm.model.dmn.HitPolicy
import org.springframework.stereotype.Component

/**
 * Corrected validation semantics (REVISION_PLAN.md, section 3). Every test case gets an outcome;
 * the table is accepted only when it parses, passes the schema check (if requested) and every case is PASS.
 *
 * `expected` of `null`, `[]` or rows whose values are all null means "no rule applies".
 * Rows are compared pairwise in result order, for every hit policy. A result output with a non-null value that the
 * case does not name is EXTRA_OUTPUT (e.g. a 2025 tax table with `militaryLevy` against 2024 tests); MISMATCH on an
 * expected key takes precedence, and the extra outputs are recorded either way.
 */
@Component
class StrictValidator(private val dmnEngine: DmnEngine) : Validator {

    override val mode = ValidatorMode.STRICT

    override fun validate(dmnXml: String, testCases: List<Map<String, Any?>>, schemaPaths: Set<String>?): ValidationReport {
        val table = try {
            DmnTable.parse(dmnEngine, dmnXml)
        } catch (e: Exception) {
            return ValidationReport(mode, accepted = false, tableError = TableError.PARSE_ERROR, tableErrorMessage = e.message ?: e.javaClass.name)
        }

        val unknownPaths = schemaPaths?.let { table.referencedPaths() - it }.orEmpty()
        val tableError = if (unknownPaths.isEmpty()) null else TableError.SCHEMA_ERROR
        val tableErrorMessage = if (unknownPaths.isEmpty()) null else "Input expressions reference paths absent from the schema: ${unknownPaths.sorted()}"

        val cases = testCases.mapIndexed { index, testCase -> evaluateCase(table, index, testCase) }
        return ValidationReport(
            mode,
            accepted = tableError == null && cases.all { it.outcome == CaseOutcome.PASS },
            tableError = tableError,
            tableErrorMessage = tableErrorMessage,
            cases = cases,
        )
    }

    private fun evaluateCase(table: DmnTable, index: Int, testCase: Map<String, Any?>): CaseResult {
        val expected = expectedRows(testCase["expected"])
            ?: return CaseResult(index, CaseOutcome.EVAL_ERROR, message = "Unsupported 'expected' format: ${testCase["expected"]}")

        val actual = try {
            table.evaluate(dmnEngine, testCase).resultList.map { it.toMap() }
        } catch (e: DmnHitPolicyException) {
            return CaseResult(index, CaseOutcome.MULTI_RESULT, message = e.message)
        } catch (e: Exception) {
            return CaseResult(index, CaseOutcome.EVAL_ERROR, message = e.message ?: e.javaClass.name)
        }

        if (expected.isEmpty()) {
            return CaseResult(index, if (actual.isEmpty()) CaseOutcome.PASS else CaseOutcome.UNEXPECTED_RESULT, actual)
        }
        if (actual.isEmpty()) {
            return CaseResult(index, CaseOutcome.EMPTY_RESULT, actual)
        }
        if (table.hitPolicy in SINGLE_HIT && actual.size > 1 && expected.size == 1) {
            return CaseResult(index, CaseOutcome.MULTI_RESULT, actual)
        }

        val mismatches = compareRows(expected, actual)
        val extraOutputs = extraOutputs(expected, actual)
        val outcome = when {
            mismatches.isNotEmpty() -> CaseOutcome.MISMATCH
            extraOutputs.isNotEmpty() -> CaseOutcome.EXTRA_OUTPUT
            else -> CaseOutcome.PASS
        }
        return CaseResult(index, outcome, actual, mismatches, extraOutputs = extraOutputs)
    }

    /** Non-null result outputs that the matching expected row does not name (`expected` = null means absent). */
    private fun extraOutputs(expected: List<Map<String, Any?>>, actual: List<Map<String, Any?>>): List<KeyMismatch> =
        (0 until minOf(expected.size, actual.size)).flatMap { row ->
            actual[row].filter { (key, value) -> value != null && key !in expected[row] }.map { (key, value) -> KeyMismatch(row, key, null, value) }
        }

    private fun compareRows(expected: List<Map<String, Any?>>, actual: List<Map<String, Any?>>): List<KeyMismatch> {
        val mismatches = mutableListOf<KeyMismatch>()
        if (expected.size != actual.size) {
            mismatches += KeyMismatch(row = -1, key = "#rows", expected = expected.size, actual = actual.size)
        }
        for (row in 0 until minOf(expected.size, actual.size)) {
            for ((key, value) in expected[row]) {
                val actualValue = actual[row][key]
                if (actualValue != value && !equalsIgnoreType(actualValue, value)) {
                    mismatches += KeyMismatch(row, key, value, actualValue)
                }
            }
        }
        return mismatches
    }

    /** Normalised expected rows; empty = "no rule applies"; null = unsupported format. */
    private fun expectedRows(expected: Any?): List<Map<String, Any?>>? {
        val rows = when (expected) {
            null -> emptyList()
            is Map<*, *> -> listOf(expected)
            is List<*> -> expected.map { it as? Map<*, *> ?: return null }
            else -> return null
        }
        @Suppress("UNCHECKED_CAST")
        return (rows as List<Map<String, Any?>>).filterNot { row -> row.values.all { it == null } }
    }

    private companion object {
        val SINGLE_HIT = setOf(HitPolicy.UNIQUE, HitPolicy.FIRST, HitPolicy.ANY, HitPolicy.PRIORITY)
    }
}
