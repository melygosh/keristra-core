package com.grimels.keristra.core.validation

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken
import com.grimels.keristra.core.ai.anthropic.AnthropicDMNService
import java.lang.reflect.Type
import org.camunda.bpm.dmn.engine.DmnDecision
import org.camunda.bpm.dmn.engine.DmnEngine
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class DmnValidator {

    @Autowired
    private val dmnEngine: DmnEngine? = null

    @Autowired
    private val logger: Logger = LoggerFactory.getLogger(DmnValidator::class.java)

    @Autowired
    private val gson: Gson? = null

    @Autowired
    private val anthropicDMNService: AnthropicDMNService? = null

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
            val decisionTable = dmnEngine?.parseDecision("decision", decisionTableModel)
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
                val result = dmnEngine?.evaluateDecision(decisionTable, variables)
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

    fun generateTestCases(input: String, schema: String): List<Map<String, Any?>> {
        if (gson == null) {
            throw IllegalStateException("Gson is not initialized")
        }
        var generateEntities = anthropicDMNService?.generateEntities(
            """
            Generate N test cases for DMN decision table validation.
            Each test case should be a JSON object with fields matching the input schema.
            Provide as many test cases as possible, covering edge cases and typical scenarios.
            Ensure the test cases are valid according to the provided JSON schema.
            Respond only with a JSON array of test case objects, without any additional text. IT IS VERY IMPORTANT!
            <objectSchema>
                $schema
            </objectSchema>
            <rules>
                $input
            </rules>
        """.trimIndent()
        ) ?: throw IllegalStateException("AnthropicDMNService is not initialized")

        if (generateEntities.startsWith("```json")) {
            generateEntities = generateEntities.removePrefix("```json").removeSuffix("```").trim()
        }

        logger.info("Generated for Validation: $generateEntities")
        val jsonArray = gson.fromJson(generateEntities, JsonArray::class.java)

        val maps = mutableListOf<Map<String, Any?>>()
        for (i in 0..<jsonArray.size()) {
            val mapType: Type? = object : TypeToken<Map<String, Any?>>() {}.getType()
            maps.add(gson.fromJson(jsonArray.get(i), mapType))
        }

        return maps
    }
}