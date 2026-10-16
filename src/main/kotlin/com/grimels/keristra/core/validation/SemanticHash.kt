package com.grimels.keristra.core.validation

import java.security.MessageDigest
import org.camunda.bpm.model.dmn.Dmn
import org.camunda.bpm.model.dmn.HitPolicy
import org.camunda.bpm.model.dmn.instance.Decision
import org.camunda.bpm.model.dmn.instance.DecisionTable

/**
 * Hash of a table's decision logic (REVISION_PLAN 4.4), used to tell whether a retry changed the logic.
 * Covered: hit policy (+ aggregator), input expressions, and per rule its non-empty (inputExpression, entry) pairs
 * and (output, entry) pairs. Ignored: ids, labels, names of inputs, column order, layout whitespace, `-` vs empty
 * entries, and rule order under hit policies where order does not change the result (all but FIRST and RULE ORDER).
 * Whitespace inside FEEL string literals is kept.
 */
object SemanticHash {

    private val ORDER_SENSITIVE = setOf(HitPolicy.FIRST, HitPolicy.RULE_ORDER)
    private val ANY_VALUE = setOf("", "-")
    private val STRING_LITERAL_OR_WHITESPACE = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|\\s+")

    /** Null when the XML is not a parseable DMN with decision id "decision". */
    fun of(dmnXml: String): String? {
        val table = try {
            val model = Dmn.readModelFromStream(dmnXml.byteInputStream(Charsets.UTF_8))
            model.getModelElementById<Decision>(DmnTable.DECISION_ID)?.expression as? DecisionTable
        } catch (e: Exception) {
            null
        } ?: return null

        val inputs = table.inputs.map { normalize(it.inputExpression?.text?.textContent) }
        val outputs = table.outputs.map { it.name ?: it.label.orEmpty() }
        val rules = table.rules.map { rule ->
            val conditions = rule.inputEntries.mapIndexedNotNull { i, entry ->
                normalize(entry.text?.textContent).takeUnless { it in ANY_VALUE }?.let { "${inputs.getOrElse(i) { "#$i" }} : $it" }
            }.sorted()
            val results = rule.outputEntries.mapIndexed { i, entry ->
                "${outputs.getOrElse(i) { "#$i" }} = ${normalize(entry.text?.textContent)}"
            }.sorted()
            "when ${conditions.joinToString(" && ")} then ${results.joinToString(" ; ")}"
        }

        val canonical = buildString {
            appendLine("hitPolicy ${table.hitPolicy}${table.aggregation?.let { " $it" }.orEmpty()}")
            appendLine("inputs ${inputs.sorted()}")
            (if (table.hitPolicy in ORDER_SENSITIVE) rules else rules.sorted()).forEach(::appendLine)
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Drops whitespace outside string literals. */
    private fun normalize(text: String?): String =
        STRING_LITERAL_OR_WHITESPACE.replace(text.orEmpty()) { if (it.value.startsWith("\"")) it.value else "" }
}
