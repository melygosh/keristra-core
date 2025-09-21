package com.grimels.keristra.core.rest

import com.grimels.keristra.core.decision.table.DecisionTableEvaluationService
import com.grimels.keristra.core.rest.bean.DecisionTableTestRequest
import com.grimels.keristra.core.rest.bean.DmnItemResponse
import org.camunda.bpm.dmn.engine.DmnDecisionTableResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/decision-tables")
class DecisionTableController {

    @Autowired
    private val decisionService: DecisionTableEvaluationService? = null

    @RequestMapping(value = ["/test"], produces = ["application/json"], method = [RequestMethod.POST])
    fun test(@RequestBody bean: DecisionTableTestRequest): List<DmnItemResponse<Any>>? {
        return this.decisionService?.evaluate(bean.decisionTable, bean.context)
    }
}
