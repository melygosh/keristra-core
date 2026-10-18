package com.grimels.keristra.core.validation

import org.camunda.bpm.dmn.engine.DmnDecision
import org.camunda.bpm.dmn.engine.DmnDecisionResult
import org.camunda.bpm.dmn.engine.DmnEngine
import org.camunda.bpm.engine.variable.Variables
import org.camunda.bpm.model.dmn.Dmn
import org.camunda.bpm.model.dmn.HitPolicy
import org.camunda.bpm.model.dmn.instance.Decision
import org.camunda.bpm.model.dmn.instance.DecisionTable

/** A parsed single-decision DMN, looked up by the decision id the generator is told to use. */
internal class DmnTable private constructor(
    val decision: DmnDecision,
    val hitPolicy: HitPolicy,
    val inputExpressions: List<String>,
) {
    /** Evaluates the test case's `object` as the variable context, exactly as V0 did. */
    fun evaluate(engine: DmnEngine, testCase: Map<String, Any?>): DmnDecisionResult {
        val variables = Variables.createVariables()
        @Suppress("UNCHECKED_CAST")
        variables.putAll(testCase["object"] as Map<String, Any?>)
        return engine.evaluateDecision(decision, variables)
    }

    /**
     * Dotted variable paths referenced by input expressions, string literals ignored. Paths rooted in a variable bound
     * by a FEEL iteration (`for d in …`, `some d in …`, `every d in …`) are not schema paths and are skipped; the
     * collection iterated over is still reported.
     */
    fun referencedPaths(): Set<String> =
        inputExpressions.flatMap { expression ->
            val code = expression.replace(STRING_LITERAL, "")
            val bound = BOUND_VARIABLE.findAll(code).map { it.groupValues[1] }.toSet()
            PATH.findAll(code).map { it.value }.filter { it.substringBefore('.') !in bound }.toList()
        }.toSet()

    companion object {
        const val DECISION_ID = "decision"
        private val STRING_LITERAL = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        private val PATH = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
        private val BOUND_VARIABLE = Regex("\\b(?:for|some|every)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\b")

        /** Same parse path as V0 (`Dmn.readModelFromStream` + `parseDecision("decision")`); throws on any failure. */
        fun parse(engine: DmnEngine, dmnXml: String): DmnTable {
            val model = Dmn.readModelFromStream(dmnXml.byteInputStream(Charsets.UTF_8))
            val decision = engine.parseDecision(DECISION_ID, model)
            val table = model.getModelElementById<Decision>(DECISION_ID).expression as DecisionTable
            return DmnTable(
                decision = decision,
                hitPolicy = table.hitPolicy,
                inputExpressions = table.inputs.map { it.inputExpression?.text?.textContent.orEmpty() },
            )
        }
    }
}
