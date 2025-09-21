package com.grimels.keristra.core

import camundajar.impl.com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.grimels.keristra.core.ai.anthropic.AnthropicDMNController
import com.grimels.keristra.core.ai.anthropic.dto.DmnGenerationRequest
import com.grimels.keristra.core.decision.table.DecisionTableEvaluationService
import com.grimels.keristra.core.model.bmi.Bmi
import com.grimels.keristra.core.model.user.User
import com.grimels.keristra.core.rest.bean.DmnItemResponse
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.reflect.Type
import java.time.Duration
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.ResponseEntity
import org.springframework.util.ResourceUtils


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestArticleTest {

    @LocalServerPort
    private val port = 0

    final var builder: RestTemplateBuilder = RestTemplateBuilder().setReadTimeout(Duration.ofSeconds(60))
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
        val testsCount = 63
        for (i in 1..testsCount) {
            val startTime = System.nanoTime()
            if (test_experiment_1()) {
                testsPassed++
                println("✅$i/$testsCount passed in ${(System.nanoTime() - startTime) / 1_000_000} ms")
            } else {
                println("❎$i/$testsCount failed in ${(System.nanoTime() - startTime) / 1_000_000} ms")
            }
        }
//            test_simple_2()
//            println("[test_simple_2] $i/25 passed")
//        }
        println("✅$testsPassed/$testsCount tests passed")
        println("❎${testsCount - testsPassed}/$testsCount tests failed")
    }

    fun test_experiment_1(): Boolean {
        assertThat(restTemplate).isNotNull();

        var dmnTxt: ResponseEntity<String>? = null
        try {
            val request = DmnGenerationRequest(
                """Hit policy is Rule Order
All assets should have a registration fee: "Value Added Product" is "Registration Fee", "Term Restriction" is "NOT_ALLOW_HIGHER", "Default VAP Amount" is empty.
If Make is "CAR_MAKE_1" or "CAR_MAKE_2" or "CAR_MAKE_3" or "CAR_MAKE_4" and Asset Segment Code is "ELECTRIC_VEHICLE", then "Value Added Product" is "Insurance", "Term Restriction" is "MATCHING", and "Default VAP Amount" is empty.
If Asset Segment Code is "ELECTRIC_VEHICLE" and Model is "MODEL_1", then "Value Added Product" is "Service and Maintenance", "Term Restriction" is "MATCHING", and "Default VAP Amount" is 5000 if subsidies are present, otherwise 20000.
If Asset Segment Code is "COMMERCIAL_VEHICLE" and Model is "MODEL_2", then set Value Added Product to "Service and Maintenance", "Term Restriction" to "MATCHING", and set "Default VAP Amount" to 7000 if subsidies are present, otherwise 32000.
If Asset Segment Code is "COMMERCIAL_VEHICLE" and Model is "MODEL_3", then set Value Added Product to "Service and Maintenance", "Term Restriction" to "MATCHING", and "Default VAP Amount" to 3000.
If Asset Segment Code is "COMMERCIAL_VEHICLE" and Model is either "MODEL_4" or "MODEL_5", then set Value Added Product to "Service and Maintenance", "Term Restriction" to "MATCHING", and Default VAP Amount to 4000.
If Asset Segment Code is "HYBRID_VEHICLE" and Model is "MODEL_6", then set Value Added Product to "Service and Maintenance", Term Restriction to "MATCHING", and Default VAP Amount to 16500.
If Asset Segment Code is "HYBRID_VEHICLE" and Model is "MODEL_7", then set Value Added Product to "Service and Maintenance", Term Restriction to "MATCHING", and Default VAP Amount to 16500.
If Asset Segment Code is "HYBRID_VEHICLE", then set Value Added Product to "Establishment Fee", Term Restriction to "NOT_ALLOW_HIGHER", and Default VAP Amount to 1000.
                """.trimIndent(),
                "users"
            )
            dmnTxt = restTemplate?.postForEntity(
                "http://localhost:$port/api/anthropic-dmn/generate",
                request,
                String::class.java,
            )
            if (dmnTxt == null || dmnTxt.body == null || dmnTxt.body!!.isBlank()) {
                println("❎Invalid DMN: empty response")
                return false
            }

            val resp1: List<DmnItemResponse<User>> =
                decisionTableEvaluationService.evaluate(dmnTxt!!.body!!, "users") ?: return false

            // All assets should have a registration fee: "Value Added Product" is "Registration Fee", "Term Restriction" is "NOT_ALLOW_HIGHER", "Default VAP Amount" is empty.
            if (!resp1.filter { it.evaluation.resultList.get(0).get("Value Added Product") == "Registration Fee" }
                    ?.all {
                        val row = it.evaluation.resultList.get(0);
                        return row != null
                                && row.get("valueAddedProduct") == "Registration Fee"
                                && row.get("termRestriction") == "NOT_ALLOW_HIGHER"
                                && row.get("defaultVAPAmount") == null
                    }!!) {
                println("Invalid DMN: invalid Registration Fee case \n$dmnTxt")
                return false
            }
            // If Make is "CAR_MAKE_1" or "CAR_MAKE_2" or "CAR_MAKE_3" or "CAR_MAKE_4" and Asset Segment Code is "ELECTRIC_VEHICLE", then "Value Added Product" is "Insurance", "Term Restriction" is "MATCHING", and "Default VAP Amount" is empty.
            if (!resp1.filter {
                    (it.item.asset?.make == "CAR_MAKE_1" || it.item.asset?.make == "CAR_MAKE_2" || it.item.asset?.make == "CAR_MAKE_3" || it.item.asset?.make == "CAR_MAKE_4")
                            && it.item.asset?.segment?.code == "ELECTRIC_VEHICLE"
                }?.all {
                    val row = it.evaluation.resultList.find { it.get("valueAddedProduct") == "Insurance" }
                    return row != null
                            && row.get("valueAddedProduct") == "Insurance"
                            && row.get("termRestriction") == "MATCHING"
                            && row.get("defaultVAPAmount") == null
                }!!) {
                println("Invalid DMN: invalid Insurance case \n$dmnTxt")
                return false
            }
            // If Asset Segment Code is "ELECTRIC_VEHICLE" and Model is "MODEL_1", then "Value Added Product" is "Service and Maintenance", "Term Restriction" is "MATCHING", and "Default VAP Amount" is 5000 if subsidies are present, otherwise 20000.
            if (!resp1.filter {
                    it.item.asset?.model == "MODEL_1" && it.item.asset?.segment?.code == "ELECTRIC_VEHICLE"
                }?.all {
                    val row = it.evaluation.resultList.find { it.get("defaultVAPAmount") == 5000 }
                    return row != null
                            && row.get("valueAddedProduct") == "Service and Maintenance"
                            && row.get("termRestriction") == "MATCHING"
                            && ((it.item.hasSubsidies && row.get("defaultVAPAmount") == 5000) || (!it.item.hasSubsidies && row.get(
                        "defaultVAPAmount"
                    ) == 20000))
                }!!) {
                println("Invalid DMN: invalid Service and Maintenance case \n$dmnTxt")
                return false
            }

            // If Asset Segment Code is "COMMERCIAL_VEHICLE" and Model is "MODEL_2", then set Value Added Product to "Service and Maintenance", "Term Restriction" to "MATCHING", and set "Default VAP Amount" to 7000 if subsidies are present, otherwise 32000.
            if (!resp1.filter {
                    it.item.asset?.model == "MODEL_2" && it.item.asset?.segment?.code == "COMMERCIAL_VEHICLE"
                }?.all {
                    val row = it.evaluation.resultList.find { it.get("defaultVAPAmount") == 7000 }
                    return row != null
                            && row.get("valueAddedProduct") == "Service and Maintenance"
                            && row.get("termRestriction") == "MATCHING"
                            && ((it.item.hasSubsidies && row.get("defaultVAPAmount") == 7000) || (!it.item.hasSubsidies && row.get(
                        "defaultVAPAmount"
                    ) == 32000))
                }!!) {
                println("Invalid DMN: invalid Service and Maintenance case \n$dmnTxt")
                return false
            }

            //If Asset Segment Code is "COMMERCIAL_VEHICLE" and Model is "MODEL_3", then set Value Added Product to "Service and Maintenance", "Term Restriction" to "MATCHING", and "Default VAP Amount" to 3000.
            if (!resp1.filter {
                    it.item.asset?.model == "MODEL_3" && it.item.asset?.segment?.code == "COMMERCIAL_VEHICLE"
                }?.all {
                    val row = it.evaluation.resultList.find { it.get("defaultVAPAmount") == 3000 }
                    return row != null
                            && row.get("valueAddedProduct") == "Service and Maintenance"
                            && row.get("termRestriction") == "MATCHING"
                            && row.get("defaultVAPAmount") == 3000
                }!!) {
                println("Invalid DMN: invalid Service and Maintenance case \n$dmnTxt")
                return false
            }

            //If Asset Segment Code is "COMMERCIAL_VEHICLE" and Model is either "MODEL_4" or "MODEL_5", then set Value Added Product to "Service and Maintenance", "Term Restriction" to "MATCHING", and Default VAP Amount to 4000.
            if (!resp1.filter {
                    (it.item.asset?.model == "MODEL_4" || it.item.asset?.model == "MODEL_5") && it.item.asset?.segment?.code == "COMMERCIAL_VEHICLE"
                }?.all {
                    val row = it.evaluation.resultList.find { it.get("defaultVAPAmount") == 4000 }
                    return row != null
                            && row.get("valueAddedProduct") == "Service and Maintenance"
                            && row.get("termRestriction") == "MATCHING"
                            && row.get("defaultVAPAmount") == 4000
                }!!) {
                println("Invalid DMN: invalid Service and Maintenance case \n$dmnTxt")
                return false
            }

            //If Asset Segment Code is "HYBRID_VEHICLE" and Model is "MODEL_6", then set Value Added Product to "Service and Maintenance", Term Restriction to "MATCHING", and Default VAP Amount to 16500.
            if (!resp1.filter {
                    it.item.asset?.model == "MODEL_6" && it.item.asset?.segment?.code == "HYBRID_VEHICLE"
                }?.all {
                    val row = it.evaluation.resultList.find { it.get("defaultVAPAmount") == 16500 }
                    return row != null
                            && row.get("valueAddedProduct") == "Service and Maintenance"
                            && row.get("termRestriction") == "MATCHING"
                            && row.get("defaultVAPAmount") == 16500
                }!!) {
                println("Invalid DMN: invalid Service and Maintenance case \n$dmnTxt")
                return false
            }

            //If Asset Segment Code is "HYBRID_VEHICLE" and Model is "MODEL_7", then set Value Added Product to "Service and Maintenance", Term Restriction to "MATCHING", and Default VAP Amount to 16500.
            if (!resp1.filter {
                    it.item.asset?.model == "MODEL_7" && it.item.asset?.segment?.code == "HYBRID_VEHICLE"
                }?.all {
                    val row = it.evaluation.resultList.find { it.get("defaultVAPAmount") == 16500 }
                    return row != null
                            && row.get("valueAddedProduct") == "Service and Maintenance"
                            && row.get("termRestriction") == "MATCHING"
                            && row.get("defaultVAPAmount") == 16500
                }!!) {
                println("Invalid DMN: invalid Service and Maintenance case \n$dmnTxt")
                return false
            }

            //If Asset Segment Code is "HYBRID_VEHICLE", then set Value Added Product to "Establishment Fee", Term Restriction to "NOT_ALLOW_HIGHER", and Default VAP Amount to 1000.
            if (!resp1.filter {
                    it.item.asset?.segment?.code == "HYBRID_VEHICLE"
                }?.all {
                    val row = it.evaluation.resultList.find { it.get("defaultVAPAmount") == 1000 }
                    return row != null
                            && row.get("valueAddedProduct") == "Establishment Fee"
                            && row.get("termRestriction") == "NOT_ALLOW_HIGHER"
                            && row.get("defaultVAPAmount") == 1000
                }!!) {
                println("Invalid DMN: invalid Service and Maintenance case \n$dmnTxt")
                return false
            }

        } catch (e: Exception) {
            println("Invalid DMN: evaluation exception \n${e.message} \n$dmnTxt")
            return false
        }
        return true
    }


//    fun test_simple_1(): Boolean {
//        assertThat(restTemplate).isNotNull();
//
//        var dmnTxt: org.springframework.http.ResponseEntity<String>? = null
//        try {
//            dmnTxt = restTemplate?.postForEntity(
//                "http://localhost:$port/api/anthropic-dmn/generate",
//                """
//                    hit policy is "FIRST"
//                    If age is less than 18 then "code" is "minor" else "code" is "adult"
//                """.trimIndent(),
//                String::class.java,
//            )
//            if (dmnTxt == null || dmnTxt.body == null || dmnTxt.body!!.isBlank()) {
//                println("❎Invalid DMN: empty response")
//                return false
//            }
////            println("[test_simple_1] dmnTxt: ${dmnTxt?.body}")
//
//            val resp1 = decisionTableEvaluationService.evaluate(dmnTxt!!.body!!) ?: return false
//            if (!resp1?.filter { it.item.age!! < 18 }?.all {
//                    it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0).get("code") == "minor"
//                }!!) {
//                println("Invalid DMN: invalid minor case \n$dmnTxt")
//                return false
//            }
//            if (!resp1?.filter { it.item.age!! > 18 }?.all {
//                    it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0).get("code") == "adult"
//                }!!) {
//                println("Invalid DMN: invalid adult case \n$dmnTxt")
//                return false
//            }
//        } catch (e: Exception) {
//            println("Invalid DMN: evaluation exception \n$dmnTxt")
//            return false
//        }
//        return true
//    }
//
//    fun test_simple_2(): Boolean {
//        assertThat(restTemplate).isNotNull();
//
//        var dmnTxt: org.springframework.http.ResponseEntity<String>? = null
//        try {
//            dmnTxt = restTemplate?.postForEntity(
//            "http://localhost:$port/api/anthropic-dmn/generate",
//            """
//                    hit policy is "RULE ORDER"
//                    If "gender" is "Female" and "age" is more than 18 and "car model year" is less than 2010 then "percent" is 0.75 and "message" is "Female with old car"
//                    If "gender" is "Female" and "age" is more than 18 and "car model year" is more than 2010 then "percent" is 0.45 and "message" is "Female with new car"
//                """.trimIndent(),
//            String::class.java,
//        )
//        if (dmnTxt == null || dmnTxt.body == null || dmnTxt.body!!.isBlank()) {
//            println("❎Invalid DMN: empty response")
//            return false
//        }
//
//        val resp1 = decisionTableEvaluationService.evaluate(dmnTxt!!.body!!) ?: return false
//        if (!resp1?.filter { it.item.gender == "Female" && it.item.age!! > 18 && it.item.car != null && it.item.car!!.modelYear!! < 2010 }?.all {
//                it.evaluation.resultList.size == 1
//                        && it.evaluation.resultList.get(0).get("percent").toString() == "0.75"
//                        && it.evaluation.resultList.get(0).get("message").toString() == "Female with old car"
//            }!!) {
//            println("Invalid DMN: invalid 0.75 case \n$dmnTxt")
//            return false
//        }
//            if (!resp1?.filter { it.item.gender == "Female" && it.item.age!! > 18 && it.item.car != null && it.item.car!!.modelYear!! > 2010 }?.all {
//                    it.evaluation.resultList.size == 1
//                            && it.evaluation.resultList.get(0).get("percent").toString() == "0.45"
//                            && it.evaluation.resultList.get(0).get("message").toString() == "Female with new car"
//                }!!) {
//                println("Invalid DMN: invalid 0.45 case \n$dmnTxt")
//                return false
//            }
//        } catch (e: Exception) {
//            println("Invalid DMN: evaluation exception ${e.message} \n$dmnTxt")
//            return false
//        }
//        return true
//    }

}
