package com.grimels.keristra.core

import com.grimels.keristra.core.ai.anthropic.AnthropicDMNController
import com.grimels.keristra.core.decision.table.DecisionTableEvaluationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.client.RestTemplateBuilder


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KeristraCoreApplicationTests {

    @LocalServerPort
    private val port = 0

    final var builder: RestTemplateBuilder = RestTemplateBuilder().setReadTimeout(java.time.Duration.ofSeconds(60))
    final var restTemplate: TestRestTemplate = TestRestTemplate(builder)

    @Autowired
    private lateinit var decisionTableEvaluationService: DecisionTableEvaluationService

    @Autowired
    private lateinit var anthropicDMNController: AnthropicDMNController

    @Test
    fun contextLoads() {
        assertThat(decisionTableEvaluationService).isNotNull();
        assertThat(anthropicDMNController).isNotNull();

        var testsPassed = 0
        val testsCount = 50
        for (i in 1..testsCount) {
            if (test_simple_2()) {
                testsPassed++
                println("✅$i/$testsCount passed")
            } else {
                println("❎$i/$testsCount failed")
            }
//            test_simple_2()
//            println("[test_simple_2] $i/25 passed")
        }
        println("✅$testsPassed/$testsCount tests passed")
        println("❎${testsCount - testsPassed}/$testsCount tests failed")
    }

    fun test_simple_1(): Boolean {
        assertThat(restTemplate).isNotNull();

        var dmnTxt: org.springframework.http.ResponseEntity<String>? = null
        try {
            dmnTxt = restTemplate?.postForEntity(
                "http://localhost:$port/api/anthropic-dmn/generate",
                """
                    hit policy is "FIRST"
                    If age is less than 18 then "code" is "minor" else "code" is "adult"
                """.trimIndent(),
                String::class.java,
            )
            if (dmnTxt == null || dmnTxt.body == null || dmnTxt.body!!.isBlank()) {
                println("❎Invalid DMN: empty response")
                return false
            }
//            println("[test_simple_1] dmnTxt: ${dmnTxt?.body}")

            val resp1 = decisionTableEvaluationService.evaluate(dmnTxt!!.body!!) ?: return false
            if (!resp1?.filter { it.item.age!! < 18 }?.all {
                    it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0).get("code") == "minor"
                }!!) {
                println("Invalid DMN: invalid minor case \n$dmnTxt")
                return false
            }
            if (!resp1?.filter { it.item.age!! > 18 }?.all {
                    it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0).get("code") == "adult"
                }!!) {
                println("Invalid DMN: invalid adult case \n$dmnTxt")
                return false
            }
        } catch (e: Exception) {
            println("Invalid DMN: evaluation exception \n$dmnTxt")
            return false
        }
        return true
    }

    fun test_simple_2(): Boolean {
        assertThat(restTemplate).isNotNull();

        var dmnTxt: org.springframework.http.ResponseEntity<String>? = null
        try {
            dmnTxt = restTemplate?.postForEntity(
            "http://localhost:$port/api/anthropic-dmn/generate",
            """
                    hit policy is "RULE ORDER"
                    If "gender" is "Female" and "age" is more than 18 and "car model year" is less than 2010 then "percent" is 0.75 and "message" is "Female with old car"
                    If "gender" is "Female" and "age" is more than 18 and "car model year" is more than 2010 then "percent" is 0.45 and "message" is "Female with new car"
                """.trimIndent(),
            String::class.java,
        )
        if (dmnTxt == null || dmnTxt.body == null || dmnTxt.body!!.isBlank()) {
            println("❎Invalid DMN: empty response")
            return false
        }

        val resp1 = decisionTableEvaluationService.evaluate(dmnTxt!!.body!!) ?: return false
        if (!resp1?.filter { it.item.gender == "Female" && it.item.age!! > 18 && it.item.car != null && it.item.car!!.modelYear!! < 2010 }?.all {
                it.evaluation.resultList.size == 1
                        && it.evaluation.resultList.get(0).get("percent").toString() == "0.75"
                        && it.evaluation.resultList.get(0).get("message").toString() == "Female with old car"
            }!!) {
            println("Invalid DMN: invalid 0.75 case \n$dmnTxt")
            return false
        }
            if (!resp1?.filter { it.item.gender == "Female" && it.item.age!! > 18 && it.item.car != null && it.item.car!!.modelYear!! > 2010 }?.all {
                    it.evaluation.resultList.size == 1
                            && it.evaluation.resultList.get(0).get("percent").toString() == "0.45"
                            && it.evaluation.resultList.get(0).get("message").toString() == "Female with new car"
                }!!) {
                println("Invalid DMN: invalid 0.45 case \n$dmnTxt")
                return false
            }
        } catch (e: Exception) {
            println("Invalid DMN: evaluation exception ${e.message} \n$dmnTxt")
            return false
        }
        return true
    }

}
