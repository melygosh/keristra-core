package com.grimels.keristra.core.decision.table

import com.grimels.keristra.core.model.bmi.BmiJsonRepo
import com.grimels.keristra.core.model.education.StudentApplicationJsonRepo
import com.grimels.keristra.core.model.hotel.HotelRepo
import com.grimels.keristra.core.model.mortgage.MortgageJsonRepo
import com.grimels.keristra.core.model.user.UserJsonRepo
import com.grimels.keristra.core.model.vap.VapJsonRepo
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
    private val vapRepo: VapJsonRepo? = null

    @Autowired
    private val hotelRepo: HotelRepo? = null

    @Autowired
    private val mortgageRepo: MortgageJsonRepo? = null

    @Autowired
    private val studentApplicationRepo: StudentApplicationJsonRepo? = null

    fun <T> evaluate(decisionTableContent: String, context: String): List<DmnItemResponse<T>>? {
        val decisionTableModel: DmnModelInstance =
            Dmn.readModelFromStream(decisionTableContent.byteInputStream(StandardCharsets.UTF_8))
        val decisionTable: DmnDecision = dmnEngine?.parseDecision("decision", decisionTableModel)
            ?: throw IllegalArgumentException("Decision Table does not exist")

        return toEvaluationResult(decisionTable, context) as List<DmnItemResponse<T>>
    }

    fun <T : Any> evaluate(decisionTableContent: String, testCases: List<T>): List<DmnItemResponse<T>>? {
        val decisionTableModel: DmnModelInstance =
            Dmn.readModelFromStream(decisionTableContent.byteInputStream(StandardCharsets.UTF_8))
        val decisionTable: DmnDecision = dmnEngine?.parseDecision("decision", decisionTableModel)
            ?: throw IllegalArgumentException("Decision Table does not exist")

        val results = testCases.map { testCase ->
            val evaluation = evaluate(decisionTable, ObjectUtils.toMap(testCase))
            DmnItemResponse(testCase, evaluation)
        }
        return results
    }

    private fun toEvaluationResult(decisionTable: DmnDecision, context: String): List<DmnItemResponse<Any>> {
        if (context == "users") {
            return userRepo?.getUsers()
                ?.map { user -> DmnItemResponse(user, evaluate(decisionTable, ObjectUtils.toMap(user))) }
                ?: emptyList()
        }
        if (context == "bmi") {
            return bmiRepo?.getBmiList()
                ?.map { user -> DmnItemResponse(user, evaluate(decisionTable, ObjectUtils.toMap(user))) }
                ?: emptyList()
        }
        if (context == "vap") {
            return vapRepo?.getBmiList()
                ?.map { vap -> DmnItemResponse(vap, evaluate(decisionTable, ObjectUtils.toMap(vap))) }
                ?: emptyList()
        }
        if (context == "education") {
            return studentApplicationRepo?.getStudentApplicationList()
                ?.map { application -> DmnItemResponse(application, evaluate(decisionTable, ObjectUtils.toMap(application))) }
                ?: emptyList()
        }
        if (context == "mortgage") {
            return mortgageRepo?.getMortgageList()
                ?.map { application -> DmnItemResponse(application, evaluate(decisionTable, ObjectUtils.toMap(application))) }
                ?: emptyList()
        }

        return hotelRepo?.getHotels()
            ?.map { user -> DmnItemResponse(user, evaluate(decisionTable, ObjectUtils.toMap(user))) }
            ?: emptyList()

    }

    private fun evaluate(decisionTable: DmnDecision, values: Map<String, Any?>): DmnDecisionTableResult {
        val variables: VariableMap = Variables.fromMap(values);


        val result: DmnDecisionTableResult = dmnEngine!!.evaluateDecisionTable(decisionTable, variables)
        return result
    }

}