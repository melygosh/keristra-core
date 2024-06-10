package com.grimels.keristra.core.ai.openai.dto

data class ChatResponse(val choices: List<Choice>? = null)

data class Choice(val index: Int = 0, val message: Message? = null)