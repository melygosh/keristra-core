package com.grimels.keristra.core.rest

import com.grimels.keristra.core.ai.anthropic.V0TestCaseGenerator
import com.grimels.keristra.core.decision.table.DecisionTableEvaluationService
import com.grimels.keristra.core.rest.bean.DecisionTableTestRequest
import com.grimels.keristra.core.rest.bean.DmnItemResponse
import com.grimels.keristra.core.rest.bean.DmnValidationRequest
import com.grimels.keristra.core.util.SchemaResolver
import com.grimels.keristra.core.validation.DmnValidationResult
import com.grimels.keristra.core.validation.DmnValidator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/decision-tables")
class DecisionTableController {

    @Autowired
    private val decisionService: DecisionTableEvaluationService? = null
    @Autowired
    private val dmnValidator: DmnValidator? = null
    @Autowired
    private val testCaseGenerator: V0TestCaseGenerator? = null

    @RequestMapping(value = ["/test"], produces = ["application/json"], method = [RequestMethod.POST])
    fun test(@RequestBody bean: DecisionTableTestRequest): List<DmnItemResponse<Any>>? {
        return this.decisionService?.evaluate(bean.decisionTable, bean.context)
    }

    @RequestMapping(value = ["/validate"], consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE], method = [RequestMethod.POST])
    fun validateDMNTableContent(@RequestBody request: DmnValidationRequest): List<DmnValidationResult>? {
        if (request.rules == null || request.context == null) {
            throw IllegalArgumentException("Rules and context are required")
        }
        if (dmnValidator == null || testCaseGenerator == null) {
            throw IllegalArgumentException("DmnValidator is not available")
        }
        val schema = SchemaResolver.getSchema(request.context)
        val testCases = testCaseGenerator.generateTestCases(request.rules, schema);
        val results = dmnValidator.validate(request.decisionTableContent, testCases)
        return results
    }
}
