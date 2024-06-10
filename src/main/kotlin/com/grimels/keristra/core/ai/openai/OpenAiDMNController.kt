package com.grimels.keristra.core.ai.openai

import camundajar.impl.com.google.gson.Gson
import com.grimels.keristra.core.ai.AiDMNGenerator
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.file.Files
import java.nio.file.Paths
import org.springframework.http.MediaType
import org.springframework.util.ResourceUtils
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/ai-dmn")
class OpenAiDMNController(
    private val openAiService: OpenAiService,
) : AiDMNGenerator {

    private val template: String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"definitions\" name=\"definitions\" namespace=\"http://camunda.org/schema/1.0/dmn\">\n" +
            "  <decision id=\"decision\" name=\"Decision Table Configuration\">\n" +
            "    <decisionTable id=\"decisionTable\" hitPolicy=\"RULE ORDER\">\n" +
            "      <input id=\"input-1\" label=\"\">\n" +
            "        <inputExpression id=\"input-1-exp\" typeRef=\"string\" />\n" +
            "      </input>\n" +
            "      <output id=\"output-1\" label=\"\" typeRef=\"string\" />\n" +
            "      <rule id=\"rule-1\">\n" +
            "        <inputEntry id=\"rule-1-input-1\">\n" +
            "          <text></text>\n" +
            "        </inputEntry>\n" +
            "        <outputEntry id=\"rule-1-output-1\">\n" +
            "          <text></text>\n" +
            "        </outputEntry>\n" +
            "      </rule>\n" +
            "    </decisionTable>\n" +
            "  </decision>\n" +
            "</definitions>"

    @RequestMapping(value = ["/generate"], produces = [MediaType.TEXT_XML_VALUE], method = [RequestMethod.POST])
    override fun generateDMNTableContent(@RequestBody request: String): String {
        val schema = getSchema()
        val chatRequest = "You are DMN 1.3 decision table XML generator. Return only and only DMN XML code for given rules and using the data schema of object." +
                "DMN template is: \"${template}\"." +
                "Object schema is: \"${schema}\"." +
                "Rules are: \"${request}\""
        val response: String = openAiService.chat(chatRequest)
            ?: throw IllegalArgumentException("No results found for $request.")
        return response
    }

    private fun getSchema(): String {
        val file: File = ResourceUtils.getFile("classpath:schema/user-schema.json")
        val encoded = Files.readAllBytes(file.toPath())
        return String(encoded)
    }

}