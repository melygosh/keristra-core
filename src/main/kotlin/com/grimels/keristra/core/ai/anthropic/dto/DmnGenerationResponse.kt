package com.grimels.keristra.core.ai.anthropic.dto

data class DmnGenerationResponse(val decisionTableContent: String? = null, var numberOfTries: Int? = 1)