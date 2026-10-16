package com.grimels.keristra.core.ai.anthropic

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * V0 test-case generation, moved verbatim out of DmnValidator (REVISION_PLAN F4) so the validator no longer
 * calls the LLM. Kept for the V0 REST endpoints; new code uses generation.tests.TestSuiteGenerator.
 */
@Service
class V0TestCaseGenerator(
    private val anthropicDMNService: AnthropicDMNService,
    private val gson: Gson,
) {

    private val logger = LoggerFactory.getLogger(V0TestCaseGenerator::class.java)

    fun generateTestCases(input: String, schema: String): List<Map<String, Any?>> {
        var generateEntities = anthropicDMNService.generateEntities(V0Prompts.testsUserMessage(input, schema))

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
