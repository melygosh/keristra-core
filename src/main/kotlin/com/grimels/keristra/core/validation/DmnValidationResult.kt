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

    private fun equalsIgnoreType(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false

        if (a is Double || b is Double) return a.toString().toDoubleOrNull() == b.toString().toDoubleOrNull()
        if (a is Float || b is Float) return a.toString().toFloatOrNull() == b.toString().toFloatOrNull()
        if (a is Int || b is Int) return a.toString().toIntOrNull() == b.toString().toIntOrNull()
        if (a is Long || b is Long) return a.toString().toLongOrNull() == b.toString().toLongOrNull()
        if (a is Boolean || b is Boolean) return a.toString().toBooleanStrictOrNull() == b.toString().toBooleanStrictOrNull()

        return a.toString() == b.toString()
    }

}