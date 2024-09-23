package com.grimels.keristra.core.rest.bean

import com.grimels.keristra.core.model.Bmi
import org.camunda.bpm.dmn.engine.DmnDecisionTableResult

data class DmnItemResponse(val item: Bmi, val evaluation: DmnDecisionTableResult)
