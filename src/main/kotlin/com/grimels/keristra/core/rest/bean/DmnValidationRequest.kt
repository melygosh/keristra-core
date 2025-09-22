package com.grimels.keristra.core.rest.bean

data class DmnValidationRequest(val decisionTableContent: String, val rules: String? = null, val context: String? = null)