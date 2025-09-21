package com.grimels.keristra.core.rest.bean

import org.camunda.bpm.dmn.engine.DmnDecisionTableResult

data class DmnItemResponse<T>(val item: T, val evaluation: DmnDecisionTableResult)
