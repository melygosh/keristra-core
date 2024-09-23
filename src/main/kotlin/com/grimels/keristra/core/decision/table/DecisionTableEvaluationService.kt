package com.grimels.keristra.core.decision.table

import com.grimels.keristra.core.model.Bmi
import com.grimels.keristra.core.model.BmiJsonRepo
import com.grimels.keristra.core.model.HotelRepo
import com.grimels.keristra.core.model.User
import com.grimels.keristra.core.model.UserJsonRepo
import com.grimels.keristra.core.rest.bean.DmnItemResponse
import com.grimels.keristra.core.util.ObjectUtils
import java.nio.charset.StandardCharsets
import org.camunda.bpm.dmn.engine.DmnDecision
import org.camunda.bpm.dmn.engine.DmnDecisionTableResult
import org.camunda.bpm.dmn.engine.DmnEngine
import org.camunda.bpm.engine.variable.VariableMap
import org.camunda.bpm.engine.variable.Variables
import org.camunda.bpm.model.dmn.Dmn
import org.camunda.bpm.model.dmn.DmnModelInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service


@Service
class DecisionTableEvaluationService {

    @Autowired
    private val dmnEngine: DmnEngine? = null

    @Autowired
    private val userRepo: UserJsonRepo? = null
    @Autowired
    private val bmiRepo: BmiJsonRepo? = null

    @Autowired
    private val hotelRepo: HotelRepo? = null

    fun toUserMap(user: User): Map<String, Any?> {
        return ObjectUtils.toMap(user)
    }

    fun toBmiMap(user: Bmi): Map<String, Any?> {
        return ObjectUtils.toMap(user)
    }

    fun evaluate(decisionTableContent: String): List<DmnItemResponse>? {
        val decisionTableModel: DmnModelInstance =
            Dmn.readModelFromStream(decisionTableContent.byteInputStream(StandardCharsets.UTF_8))
        val decisionTable: DmnDecision = dmnEngine?.parseDecision("decision", decisionTableModel)
            ?: throw IllegalArgumentException("Decision Table does not exist")

        return toEvaluationResult(decisionTable)
    }

    private fun toEvaluationResult(decisionTable: DmnDecision): List<DmnItemResponse> {
        return bmiRepo?.getBmiList()
            ?.map { user -> DmnItemResponse(user, evaluate(decisionTable, toBmiMap(user))) }
            ?: emptyList()
    }

    private fun evaluate(decisionTable: DmnDecision, values: Map<String, Any?>): DmnDecisionTableResult {
        val variables: VariableMap = Variables.fromMap(values);


        val result: DmnDecisionTableResult = dmnEngine!!.evaluateDecisionTable(decisionTable, variables)
        return result
    }

}