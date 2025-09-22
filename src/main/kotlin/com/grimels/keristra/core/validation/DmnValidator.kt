package com.grimels.keristra.core.validation

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken
import com.grimels.keristra.core.ai.anthropic.AnthropicDMNService
import com.grimels.keristra.core.model.user.User
import java.lang.reflect.Type
import org.camunda.bpm.dmn.engine.DmnDecision
import org.camunda.bpm.dmn.engine.DmnEngine
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class DmnValidator {

    @Autowired
    private val dmnEngine: DmnEngine? = null

    @Autowired
    private val anthropicDMNService: AnthropicDMNService? = null


    public fun validate(decisionTableContent: String, input: String, schema: String): List<DmnValidationResult>? {
        val decisionTable = parseDecisionTable(decisionTableContent) ?: return null
        val testCases = generateTestCases(input, schema)
        return validateDmnTableOnTestCases(decisionTable, testCases)
    }

    private fun parseDecisionTable(decisionTableContent: String): DmnDecision? {
        return try {
            val decisionTableModel = org.camunda.bpm.model.dmn.Dmn.readModelFromStream(
                decisionTableContent.byteInputStream(Charsets.UTF_8)
            )
            val decisionTable = dmnEngine?.parseDecision("decision", decisionTableModel)
                ?: throw IllegalArgumentException("Decision Table does not exist")
            decisionTable
        } catch (e: Exception) {
            null
        }
    }

    private fun validateDmnTableOnTestCases(
        decisionTable: DmnDecision,
        testCases: List<Map<String, Any>>
    ): List<DmnValidationResult> {
        val results: MutableList<DmnValidationResult> = mutableListOf()
        for (testCaseElement in testCases) {
            try {
                val variables = org.camunda.bpm.engine.variable.Variables.createVariables()
                variables.putAll(testCaseElement.get("object") as Map<String, Any>)
                println("Testing with variables: $variables")
                val result = dmnEngine?.evaluateDecision(decisionTable, variables)
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

    private fun generateTestCases(input: String, schema: String): List<Map<String, Any>> {
        val generateEntities = anthropicDMNService?.generateEntities(
            """
            Generate N test cases for DMN decision table validation.
            Each test case should be a JSON object with fields matching the input schema.
            Provide as many test cases as possible, covering edge cases and typical scenarios.
            Ensure the test cases are valid according to the provided JSON schema.
            Respond only with a JSON array of test case objects, without any additional text.
            <objectSchema>
                $schema
            </objectSchema>
            <rules>
                $input
            </rules>
        """.trimIndent()
        )

        val jsonArray = com.google.gson.JsonParser.parseString(generateEntities) as JsonArray

        val gson = com.google.gson.Gson()
        val maps = mutableListOf<Map<String, Any>>()
        for (i in 0..<jsonArray.size()) {
            val mapType: Type? = object : TypeToken<Map<String, Any>>() {}.getType()
            maps.add(gson.fromJson(jsonArray.get(i), mapType))
        }

        return maps
    }
}