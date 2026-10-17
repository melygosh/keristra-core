package com.grimels.keristra.core.mutation

import com.grimels.keristra.core.validation.DmnTable
import com.grimels.keristra.core.validation.StrictValidator
import java.math.BigDecimal
import org.camunda.bpm.model.dmn.Dmn
import org.camunda.bpm.model.dmn.DmnModelInstance
import org.camunda.bpm.model.dmn.HitPolicy
import org.camunda.bpm.model.dmn.instance.Decision
import org.camunda.bpm.model.dmn.instance.DecisionTable

/** Mutation operators of REVISION_PLAN 4.7 (experiment E1). */
enum class MutationOperator { THRESHOLD_SHIFT, BOUNDARY_FLIP, RULE_SWAP, RULE_DROP, OUTPUT_SWAP, CONDITION_DROP, HIT_POLICY_CHANGE }

/**
 * Where a mutant can behave differently: the rules it touches (by id; empty = the whole table), the input expression
 * or output name of the changed column, and the entry text (or hit policy) before and after.
 */
data class MutationRegion(val ruleIds: List<String>, val input: String?, val before: String?, val after: String?)

data class Mutant(val id: String, val operator: MutationOperator, val xml: String, val region: MutationRegion)

/**
 * Generates first-order mutants of a reference DMN: every mutant is the reference with exactly one change.
 * - THRESHOLD_SHIFT: each numeric literal in an input entry, ±1 for integers, ±1% for decimals.
 * - BOUNDARY_FLIP: `<`↔`<=`, `>`↔`>=`, and each bracket of a range (`[`↔`(`, `]`↔`)`; `]a..` counts as open).
 * - RULE_SWAP: adjacent rules, only under FIRST and RULE ORDER, where order changes the result.
 * - RULE_DROP: each rule, a default rule included.
 * - OUTPUT_SWAP: each output entry replaced by every other value that occurs in its column.
 * - CONDITION_DROP: each non-empty input entry emptied (any value).
 * - HIT_POLICY_CHANGE: every other policy the table can be evaluated under (UNIQUE, FIRST, ANY, COLLECT, RULE ORDER).
 * Text inside FEEL string literals is never mutated. Equivalent mutants are possible (e.g. swapping two rules with the
 * same output) and are left to the analysis.
 */
object DmnMutator {

    private val STRING_LITERAL = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val NUMBER = Regex("(?<!\\w)-?\\d+(?:\\.\\d+)?(?!\\w)")
    private val COMPARISON = Regex("<=|>=|<|>")
    private val RANGE = Regex("([\\[(\\]])\\s*[^\\[\\]()]+?\\s*\\.\\.\\s*[^\\[\\]()]+?\\s*([\\])\\[])")
    private val ORDER_SENSITIVE = setOf(HitPolicy.FIRST, HitPolicy.RULE_ORDER)
    private val TARGET_POLICIES = listOf(HitPolicy.UNIQUE, HitPolicy.FIRST, HitPolicy.ANY, HitPolicy.COLLECT, HitPolicy.RULE_ORDER)
    private val ANY_VALUE = setOf("", "-")

    fun mutants(referenceXml: String, operators: Set<MutationOperator> = MutationOperator.values().toSet()): List<Mutant> {
        val reference = table(read(referenceXml))
        val inputs = reference.inputs.map { it.inputExpression?.text?.textContent.orEmpty() }
        val outputs = reference.outputs.map { it.name ?: it.label.orEmpty() }
        val rules = reference.rules.toList()
        val result = mutableListOf<Mutant>()

        fun emit(operator: MutationOperator, key: String, region: MutationRegion, change: (DecisionTable) -> Unit) {
            val model = read(referenceXml)
            change(table(model))
            result += Mutant("$operator:$key", operator, Dmn.convertToString(model), region)
        }

        fun eachInputEntry(action: (ruleIndex: Int, ruleId: String, inputIndex: Int, text: String) -> Unit) =
            rules.forEachIndexed { r, rule ->
                rule.inputEntries.forEachIndexed { i, entry -> action(r, rule.id, i, entry.text?.textContent.orEmpty()) }
            }

        fun setInput(table: DecisionTable, ruleIndex: Int, inputIndex: Int, text: String) {
            table.rules.toList()[ruleIndex].inputEntries.toList()[inputIndex].text.textContent = text
        }

        if (MutationOperator.THRESHOLD_SHIFT in operators) eachInputEntry { r, ruleId, i, text ->
            for (number in matchesOutsideStrings(text, NUMBER)) {
                for ((sign, shifted) in listOf("+" to shift(number.value, up = true), "-" to shift(number.value, up = false))) {
                    val after = text.replaceRange(number.range, shifted)
                    emit(MutationOperator.THRESHOLD_SHIFT, "$ruleId/${i + 1}/${number.range.first}$sign", MutationRegion(listOf(ruleId), inputs[i], text, after)) {
                        setInput(it, r, i, after)
                    }
                }
            }
        }

        if (MutationOperator.BOUNDARY_FLIP in operators) eachInputEntry { r, ruleId, i, text ->
            val flips = matchesOutsideStrings(text, COMPARISON).map { it.range to FLIP_COMPARISON.getValue(it.value) } +
                matchesOutsideStrings(text, RANGE).flatMap { range ->
                    val left = range.groups[1]!!
                    val right = range.groups[2]!!
                    listOf(left.range to FLIP_LEFT.getValue(left.value), right.range to FLIP_RIGHT.getValue(right.value))
                }
            for ((range, replacement) in flips) {
                val after = text.replaceRange(range, replacement)
                emit(MutationOperator.BOUNDARY_FLIP, "$ruleId/${i + 1}/${range.first}", MutationRegion(listOf(ruleId), inputs[i], text, after)) {
                    setInput(it, r, i, after)
                }
            }
        }

        if (MutationOperator.RULE_SWAP in operators && reference.hitPolicy in ORDER_SENSITIVE) {
            for (r in 0 until rules.size - 1) {
                val ids = listOf(rules[r].id, rules[r + 1].id)
                emit(MutationOperator.RULE_SWAP, ids.joinToString("/"), MutationRegion(ids, null, null, null)) { table ->
                    val ordered = table.rules.toMutableList().apply { add(r, removeAt(r + 1)) }
                    table.rules.clear()
                    table.rules.addAll(ordered)
                }
            }
        }

        if (MutationOperator.RULE_DROP in operators) rules.forEachIndexed { r, rule ->
            emit(MutationOperator.RULE_DROP, rule.id, MutationRegion(listOf(rule.id), null, null, null)) { table ->
                table.rules.remove(table.rules.toList()[r])
            }
        }

        if (MutationOperator.OUTPUT_SWAP in operators) outputs.forEachIndexed { o, output ->
            val values = rules.map { it.outputEntries.toList()[o].text?.textContent.orEmpty().trim() }.distinct()
            rules.forEachIndexed { r, rule ->
                val current = rule.outputEntries.toList()[o].text?.textContent.orEmpty().trim()
                for (value in values - current) {
                    emit(MutationOperator.OUTPUT_SWAP, "${rule.id}/$output/$value", MutationRegion(listOf(rule.id), output, current, value)) {
                        it.rules.toList()[r].outputEntries.toList()[o].text.textContent = value
                    }
                }
            }
        }

        if (MutationOperator.CONDITION_DROP in operators) eachInputEntry { r, ruleId, i, text ->
            if (text.trim() !in ANY_VALUE) {
                emit(MutationOperator.CONDITION_DROP, "$ruleId/${i + 1}", MutationRegion(listOf(ruleId), inputs[i], text, "")) {
                    setInput(it, r, i, "")
                }
            }
        }

        if (MutationOperator.HIT_POLICY_CHANGE in operators) {
            for (policy in TARGET_POLICIES - reference.hitPolicy) {
                emit(MutationOperator.HIT_POLICY_CHANGE, policyName(policy), MutationRegion(emptyList(), null, policyName(reference.hitPolicy), policyName(policy))) {
                    it.hitPolicy = policy
                }
            }
        }
        return result
    }

    private val FLIP_COMPARISON = mapOf("<=" to "<", "<" to "<=", ">=" to ">", ">" to ">=")
    private val FLIP_LEFT = mapOf("[" to "(", "(" to "[", "]" to "[")
    private val FLIP_RIGHT = mapOf("]" to ")", ")" to "]", "[" to "]")

    private fun read(xml: String): DmnModelInstance = Dmn.readModelFromStream(xml.byteInputStream(Charsets.UTF_8))

    private fun table(model: DmnModelInstance): DecisionTable =
        model.getModelElementById<Decision>(DmnTable.DECISION_ID)?.expression as? DecisionTable
            ?: throw IllegalArgumentException("Reference has no decision table with id '${DmnTable.DECISION_ID}'")

    /** Matches of [pattern] in [text] with string literals masked, so their content is never mutated. */
    private fun matchesOutsideStrings(text: String, pattern: Regex): List<MatchResult> {
        val masked = STRING_LITERAL.replace(text) { "\u0000".repeat(it.value.length) }
        return pattern.findAll(masked).toList()
    }

    private fun shift(literal: String, up: Boolean): String {
        val value = BigDecimal(literal)
        val shifted = if ('.' in literal) value.multiply(BigDecimal(if (up) "1.01" else "0.99")) else value + (if (up) BigDecimal.ONE else BigDecimal.ONE.negate())
        return shifted.stripTrailingZeros().toPlainString()
    }

    private fun policyName(policy: HitPolicy) = policy.name.replace('_', ' ')
}

object MutationAnalysis {
    /** Kill = at least one test case of the suite fails under strict validation (REVISION_PLAN 4.7). */
    fun isKilled(mutant: Mutant, testCases: List<Map<String, Any?>>, validator: StrictValidator): Boolean =
        !validator.validate(mutant.xml, testCases).accepted
}
