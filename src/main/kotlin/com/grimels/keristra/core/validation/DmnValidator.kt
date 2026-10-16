package com.grimels.keristra.core.validation

import org.camunda.bpm.dmn.engine.DmnDecision
import org.camunda.bpm.dmn.engine.DmnEngine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** V0 validation (see LenientValidator for the replica used by the experiment harness). Test generation moved to V0TestCaseGenerator (F4). */
@Service
class DmnValidator(private val dmnEngine: DmnEngine) {

    private val logger = LoggerFactory.getLogger(DmnValidator::class.java)

    fun isValidDMN(decisionTableContent: String, testCases: List<Map<String, Any?>>): List<String> {
        val results = validate(decisionTableContent, testCases)
        return results?.mapNotNull { it.isValid() }?.toList() ?: listOf()
    }

    fun validate(decisionTableContent: String, testCases: List<Map<String, Any?>>): List<DmnValidationResult>? {
        val decisionTable = parseDecisionTable(decisionTableContent) ?: return null
        return validateDmnTableOnTestCases(decisionTable, testCases)
    }

    private fun parseDecisionTable(decisionTableContent: String): DmnDecision? {
        return try {
            val decisionTableModel = org.camunda.bpm.model.dmn.Dmn.readModelFromStream(
                decisionTableContent.byteInputStream(Charsets.UTF_8)
            )
            val decisionTable = dmnEngine.parseDecision("decision", decisionTableModel)
                ?: throw IllegalArgumentException("Decision Table does not exist")
            decisionTable
        } catch (e: Exception) {
            logger.error("Error parsing DMN decision table: ${e.message}", e)
            null
        }
    }

    private fun validateDmnTableOnTestCases(
        decisionTable: DmnDecision,
        testCases: List<Map<String, Any?>>
    ): List<DmnValidationResult> {
        val results: MutableList<DmnValidationResult> = mutableListOf()
        for (testCaseElement in testCases) {
            try {
                val variables = org.camunda.bpm.engine.variable.Variables.createVariables()
                variables.putAll(testCaseElement.get("object") as Map<String, Any>)
                println("Testing with variables: $variables")
                logger.info("Testing with variables: $testCaseElement")
                val result = dmnEngine.evaluateDecision(decisionTable, variables)
                logger.info("Test result: $result")
                results.add(DmnValidationResult(testCaseElement, result))
            } catch (e: Exception) {
                val errorResp = DmnValidationResult(testCaseElement, null).apply {
                    setErrorMessage(e.message ?: "Unknown error")
                }
                results.add(errorResp)
            }
        }
        return results
    }
}
