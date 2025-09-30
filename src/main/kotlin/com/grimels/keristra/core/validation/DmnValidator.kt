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

    fun isValidDMN(decisionTableContent: String, testCases: List<Map<String, Any>>): List<String> {
        val results = validate(decisionTableContent, testCases)
        return results?.mapNotNull { it.isValid() }?.toList() ?: listOf()
    }

    fun validate(decisionTableContent: String, testCases: List<Map<String, Any>>): List<DmnValidationResult>? {
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
        testCases: List<Map<String, Any>>
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

     fun generateTestCases(input: String, schema: String): List<Map<String, Any>> {
        if (gson == null) {
            throw IllegalStateException("Gson is not initialized")
        }
//        var generateEntities = anthropicDMNService?.generateEntities(
//            """
//            Generate N test cases for DMN decision table validation.
//            Each test case should be a JSON object with fields matching the input schema.
//            Provide as many test cases as possible, covering edge cases and typical scenarios.
//            Ensure the test cases are valid according to the provided JSON schema.
//            Respond only with a JSON array of test case objects, without any additional text. IT IS VERY IMPORTANT!
//            <objectSchema>
//                $schema
//            </objectSchema>
//            <rules>
//                $input
//            </rules>
//        """.trimIndent()
//        ) ?: throw IllegalStateException("AnthropicDMNService is not initialized")

         var generateEntities = """
             [
               {
                 "name": "Automatic Rejection - Low Math Score",
                 "description": "Student with math score below 140 and physics score below 140 should be automatically rejected",
                 "expected": [
                   { "status": "Автоматично Відхилено" }
                 ],
                 "object": {
                   "student": { "id": 1, "first_name": "Олександр", "last_name": "Коваленко", "age": 18 },
                   "K1": { "name": "Українська Мова", "score": 120 },
                   "K2": { "name": "Математика", "score": 135 },
                   "K3": { "name": "Історія України", "score": 110 },
                   "K4_1": { "name": "Фізика", "score": 125 }
                 }
               },
               {
                 "name": "Automatic Acceptance - High Math Score",
                 "description": "Student with math score 140 or above should be automatically accepted",
                 "expected": [
                   { "status": "Автоматично Прийнято" }
                 ],
                 "object": {
                   "student": { "id": 2, "first_name": "Марія", "last_name": "Шевченко", "age": 17 },
                   "K1": { "name": "Українська Мова", "score": 130 },
                   "K2": { "name": "Математика", "score": 140 },
                   "K3": { "name": "Історія України", "score": 125 },
                   "K4_2": { "name": "Хімія", "score": 115 }
                 }
               },
               {
                 "name": "Automatic Acceptance - High Physics Score",
                 "description": "Student with physics score above 140 should be automatically accepted even with low math",
                 "expected": [
                   { "status": "Автоматично Прийнято" }
                 ],
                 "object": {
                   "student": { "id": 3, "first_name": "Дмитро", "last_name": "Іваненко", "age": 19 },
                   "K1": { "name": "Українська Мова", "score": 125 },
                   "K2": { "name": "Математика", "score": 130 },
                   "K3": { "name": "Історія України", "score": 120 },
                   "K4_1": { "name": "Фізика", "score": 145 }
                 }
               },
               {
                 "name": "High Priority - Average Above 185",
                 "description": "Student with average K1, K2, K3 score above 185 should get high priority",
                 "expected": [
                   { "status": "Високий Пріоритет" }
                 ],
                 "object": {
                   "student": { "id": 4, "first_name": "Анна", "last_name": "Мельник", "age": 18 },
                   "K1": { "name": "Українська Мова", "score": 190 },
                   "K2": { "name": "Математика", "score": 185 },
                   "K3": { "name": "Історія України", "score": 188 },
                   "K4_3": { "name": "Іноземна Мова", "score": 170 }
                 }
               },
               {
                 "name": "Edge Case - Math Score Exactly 140",
                 "description": "Student with exactly 140 math score should be accepted",
                 "expected": [
                   { "status": "Автоматично Прийнято" }
                 ],
                 "object": {
                   "student": { "id": 5, "first_name": "Петро", "last_name": "Бондаренко", "age": 18 },
                   "K1": { "name": "Українська Мова", "score": 115 },
                   "K2": { "name": "Математика", "score": 140 },
                   "K3": { "name": "Історія України", "score": 105 },
                   "K4_4": { "name": "Біологія", "score": 100 }
                 }
               },
               {
                 "name": "Edge Case - Physics Score Exactly 140",
                 "description": "Student with exactly 140 physics score should be accepted",
                 "expected": [
                   { "status": "Автоматично Прийнято" }
                 ],
                 "object": {
                   "student": { "id": 6, "first_name": "Софія", "last_name": "Морозова", "age": 17 },
                   "K1": { "name": "Українська Мова", "score": 120 },
                   "K2": { "name": "Математика", "score": 135 },
                   "K3": { "name": "Історія України", "score": 110 },
                   "K4_1": { "name": "Фізика", "score": 140 }
                 }
               },
               {
                 "name": "Edge Case - Average Exactly 185",
                 "description": "Student with average exactly 185 should not get high priority",
                 "expected": [
                   { "status": "Автоматично Прийнято" }
                 ],
                 "object": {
                   "student": { "id": 7, "first_name": "Андрій", "last_name": "Кравченко", "age": 18 },
                   "K1": { "name": "Українська Мова", "score": 180 },
                   "K2": { "name": "Математика", "score": 185 },
                   "K3": { "name": "Історія України", "score": 190 },
                   "K4_5": { "name": "Українська література", "score": 160 }
                 }
               },
               {
                 "name": "Minimum Scores",
                 "description": "Student with all minimum scores should be rejected",
                 "expected": [
                   { "status": "Автоматично Відхилено" }
                 ],
                 "object": {
                   "student": { "id": 8, "first_name": "Ірина", "last_name": "Савченко", "age": 17 },
                   "K1": { "name": "Українська Мова", "score": 0 },
                   "K2": { "name": "Математика", "score": 0 },
                   "K3": { "name": "Історія України", "score": 0 },
                   "K4_6": { "name": "Географія", "score": 0 }
                 }
               },
               {
                 "name": "Maximum Scores",
                 "description": "Student with maximum scores should get high priority",
                 "expected": [
                   { "status": "Високий Пріоритет" }
                 ],
                 "object": {
                   "student": { "id": 9, "first_name": "Володимир", "last_name": "Лисенко", "age": 18 },
                   "K1": { "name": "Українська Мова", "score": 200 },
                   "K2": { "name": "Математика", "score": 200 },
                   "K3": { "name": "Історія України", "score": 200 },
                   "K4_1": { "name": "Фізика", "score": 200 }
                 }
               },
               {
                 "name": "High Math Low Physics",
                 "description": "Student with high math but low physics should be accepted",
                 "expected": [
                   { "status": "Автоматично Прийнято" }
                 ],
                 "object": {
                   "student": { "id": 10, "first_name": "Катерина", "last_name": "Гриценко", "age": 19 },
                   "K1": { "name": "Українська Мова", "score": 125 },
                   "K2": { "name": "Математика", "score": 160 },
                   "K3": { "name": "Історія України", "score": 115 },
                   "K4_1": { "name": "Фізика", "score": 90 }
                 }
               },
               {
                 "name": "Low Math High Physics",
                 "description": "Student with low math but high physics should be accepted",
                 "expected": [
                   { "status": "Автоматично Прийнято" }
                 ],
                 "object": {
                   "student": { "id": 11, "first_name": "Роман", "last_name": "Попович", "age": 18 },
                   "K1": { "name": "Українська Мова", "score": 110 },
                   "K2": { "name": "Математика", "score": 120 },
                   "K3": { "name": "Історія України", "score": 105 },
                   "K4_1": { "name": "Фізика", "score": 175 }
                 }
               },
               {
                 "name": "Borderline Average Case 1",
                 "description": "Student with average just above 185",
                 "expected": [
                   { "status": "Високий Пріоритет" }
                 ],
                 "object": {
                   "student": { "id": 12, "first_name": "Оксана", "last_name": "Ткаченко", "age": 17 },
                   "K1": { "name": "Українська Мова", "score": 186 },
                   "K2": { "name": "Математика", "score": 185 },
                   "K3": { "name": "Історія України", "score": 186 },
                   "K4_2": { "name": "Хімія", "score": 140 }
                 }
               },
               {
                 "name": "Multiple K4 Subjects",
                 "description": "Student with multiple K4 subjects, high math score",
                 "expected": [
                   { "status": "Автоматично Прийнято" }
                 ],
                 "object": {
                   "student": { "id": 13, "first_name": "Максим", "last_name": "Білоус", "age": 18 },
                   "K1": { "name": "Українська Мова", "score": 135 },
                   "K2": { "name": "Математика", "score": 155 },
                   "K3": { "name": "Історія України", "score": 128 },
                   "K4_1": { "name": "Фізика", "score": 120 },
                   "K4_2": { "name": "Хімія", "score": 110 },
                   "K4_3": { "name": "Іноземна Мова", "score": 145 }
                 }
               },
               {
                 "name": "Older Student Edge Case",
                 "description": "Older student with borderline scores",
                 "expected": [
                   { "status": "Автоматично Відхилено" }
                 ],
                 "object": {
                   "student": { "id": 14, "first_name": "Віктор", "last_name": "Сидоренко", "age": 25 },
                   "K1": { "name": "Українська Мова", "score": 139 },
                   "K2": { "name": "Математика", "score": 139 },
                   "K3": { "name": "Історія України", "score": 138 },
                   "K4_4": { "name": "Біологія", "score": 139 }
                 }
               },
               {
                 "name": "Young Student High Performance",
                 "description": "Young student with high performance",
                 "expected": [
                   { "status": "Високий Пріоритет" }
                 ],
                 "object": {
                   "student": { "id": 15, "first_name": "Єва", "last_name": "Романенко", "age": 16 },
                   "K1": { "name": "Українська Мова", "score": 195 },
                   "K2": { "name": "Математика", "score": 192 },
                   "K3": { "name": "Історія України", "score": 188 },
                   "K4_5": { "name": "Українська література", "score": 180 }
                 }
               }
             ]
         """.trimIndent()

        if (generateEntities.startsWith("```json")) {
            generateEntities = generateEntities.removePrefix("```json").removeSuffix("```").trim()
        }


        logger.info("Generated for Validation: $generateEntities")
        val jsonArray = gson.fromJson(generateEntities, JsonArray::class.java)

        val maps = mutableListOf<Map<String, Any>>()
        for (i in 0..<jsonArray.size()) {
            val mapType: Type? = object : TypeToken<Map<String, Any>>() {}.getType()
            maps.add(gson.fromJson(jsonArray.get(i), mapType))
        }

        return maps
    }
}