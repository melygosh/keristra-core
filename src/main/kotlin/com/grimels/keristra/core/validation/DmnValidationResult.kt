package com.grimels.keristra.core.validation

import org.camunda.bpm.dmn.engine.DmnDecisionResult

class DmnValidationResult(val case: Map<String, Any?>?, val result: DmnDecisionResult?) {

    private var errorMessage: String? = null

    fun setErrorMessage(message: String) {
        errorMessage = message
    }

    fun getErrorMessage(): String? {
        return errorMessage
    }

    fun isValid(): String? {
        if (result == null || errorMessage != null) {
            return "Error: ${errorMessage ?: "No result"}"
        }
        val expectedResult = case?.get("expected") as List<Map<String, Any?>>
        for (item in result) {
            for (expected in expectedResult) {
                for ((key, value) in expected) {
                    val actualValue = item.get(key)
                    if (actualValue != value && !equalsIgnoreType(actualValue, value)) {
                        val studentId = ((case.get("object") as Map<String, Any>).get("application") as Map<String, Any>).get("id");
                        return "[$studentId] Mismatch on key '$key': expected '$value', got '$actualValue'"
                    }
                }
            }
        }
        return null
    }

}