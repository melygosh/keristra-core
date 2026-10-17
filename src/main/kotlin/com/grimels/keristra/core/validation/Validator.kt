package com.grimels.keristra.core.validation

enum class ValidatorMode { LENIENT, STRICT }

/** Per-test-case outcome. LENIENT only ever produces PASS, MISMATCH and EVAL_ERROR (that is all V0 can tell apart). */
enum class CaseOutcome { PASS, MISMATCH, EMPTY_RESULT, UNEXPECTED_RESULT, MULTI_RESULT, EVAL_ERROR, EXTRA_OUTPUT }

enum class TableError { PARSE_ERROR, SCHEMA_ERROR }

/** `row = -1, key = "#rows"` records a difference in the number of result rows. */
data class KeyMismatch(val row: Int, val key: String, val expected: Any?, val actual: Any?)

data class CaseResult(
    val index: Int,
    val outcome: CaseOutcome,
    val actual: List<Map<String, Any?>> = emptyList(),
    val mismatches: List<KeyMismatch> = emptyList(),
    val message: String? = null,
    val extraOutputs: List<KeyMismatch> = emptyList(),
)

data class ValidationReport(
    val mode: ValidatorMode,
    val accepted: Boolean,
    val tableError: TableError? = null,
    val tableErrorMessage: String? = null,
    val cases: List<CaseResult> = emptyList(),
) {
    val counts: Map<CaseOutcome, Int>
        get() = cases.groupingBy { it.outcome }.eachCount()
}

/**
 * Checks a DMN table against test cases of the form `{"object": {...}, "expected": [{...}] | [] | null}`.
 * [schemaPaths], when given, enables the table-level SCHEMA_ERROR check (see [SchemaPaths]).
 */
interface Validator {
    val mode: ValidatorMode
    fun validate(dmnXml: String, testCases: List<Map<String, Any?>>, schemaPaths: Set<String>? = null): ValidationReport
}
