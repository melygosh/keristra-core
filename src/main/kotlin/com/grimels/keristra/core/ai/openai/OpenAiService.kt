package com.grimels.keristra.core.ai.openai

import com.grimels.keristra.core.ai.openai.dto.ChatRequest
import com.grimels.keristra.core.ai.openai.dto.ChatResponse
import com.grimels.keristra.core.ai.openai.dto.Message
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.client.RestTemplate


@Service
class OpenAiService {

    @Qualifier("openaiRestTemplate")
    @Autowired
    private lateinit var restTemplate: RestTemplate

    @Value("\${openai.model}")
    private lateinit var model: String

    @Value("\${openai.api.url}")
    private lateinit var apiUrl: String

    fun chat(prompt: String?): String? {
        // create a request
        val request = ChatRequest(model, prompt)

        // call the API
        val response: ChatResponse? = restTemplate.postForObject(apiUrl, request, ChatResponse::class.java)

        if (response?.choices == null || response.choices.isEmpty()) {
            return "No response"
        }


        // return the first response
        return response.choices.get(0).message?.content
    }

}