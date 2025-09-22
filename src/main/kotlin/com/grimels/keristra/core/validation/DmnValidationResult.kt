package com.grimels.keristra.core.validation

import org.camunda.bpm.dmn.engine.DmnDecisionResult

class DmnValidationResult(val case: Map<String, Any>?, val result: DmnDecisionResult?) {

    private var errorMessage: String? = null

    fun setErrorMessage(message: String) {
        errorMessage = message
    }

    fun getErrorMessage(): String? {
        return errorMessage
    }

}