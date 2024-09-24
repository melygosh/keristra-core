package com.grimels.keristra.core.ai.anthropic.dto

data class DmnGenerationRequest(val rules: String? = null, val context: String? = null)