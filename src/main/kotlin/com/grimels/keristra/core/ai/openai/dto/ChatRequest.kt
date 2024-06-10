package com.grimels.keristra.core.ai.openai.dto


data class ChatRequest(val model: String, private val prompt: String?) {
    val messages: MutableList<Message> = ArrayList()
    val n = 1
    val temperature = 0.0

    init {
        messages.add(Message("user", prompt))
    }
}