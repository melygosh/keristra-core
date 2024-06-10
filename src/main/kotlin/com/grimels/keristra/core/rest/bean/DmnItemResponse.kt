package com.grimels.keristra.core.rest.bean

import org.camunda.bpm.dmn.engine.DmnDecisionTableResult

data class DmnItemResponse(val item: Any, val evaluation: DmnDecisionTableResult)
