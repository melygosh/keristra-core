package com.grimels.keristra.core

import com.grimels.keristra.core.ai.anthropic.AnthropicDMNController
import com.grimels.keristra.core.ai.anthropic.dto.DmnGenerationRequest
import com.grimels.keristra.core.decision.table.DecisionTableEvaluationService
import com.grimels.keristra.core.model.bmi.Bmi
import com.grimels.keristra.core.model.user.User
import com.grimels.keristra.core.rest.bean.DmnItemResponse
import java.util.Locale
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
        val testsCount = 200
        for (i in 1..testsCount) {
            if (test_experiment_1()) {
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


    fun test_experiment_1(): Boolean {
        assertThat(restTemplate).isNotNull();

        var dmnTxt: org.springframework.http.ResponseEntity<String>? = null
        try {
            val request = DmnGenerationRequest(
                """Hit policy is First
If age is between 18 and 25 (without 25) the creditRisk is 0.6
If age is between 25 and 40 then creditRisk is 0.3
If age is more than 40 then creditRisk 0.45
Else creditRisk is 0
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

            // If age is between 18 and 25 (without 25) the credit risk is 0.6
            if (!resp1?.filter { (it.item as User).age!! >= 18 || (it.item as User).age!! < 25 }?.all {
                    if (it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0)
                            .get("creditRisk") == 0.6
                    ) {
                        println("Invalid DMN: \n ${it.evaluation.resultList} \n ${(it.item as User).age}")
                        return false
                    }
                    return true
                }!!) {
                println("Invalid DMN: invalid 0.6 case \n${dmnTxt.body}")
                return false
            }
            // If age is between 25 and 40 then credit risk is 0.3
            if (!resp1?.filter { (it.item as User).age!! >= 25 || (it.item as User).age!! <= 40 }?.all {
                    if (it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0)
                            .get("creditRisk") == 0.3
                    ) {
                        println("Invalid DMN: ${(it.item as User).age}")
                        return false
                    }
                    return true
                }!!) {
                println("Invalid DMN: invalid 0.3 case \n${dmnTxt.body}")
                return false
            }
            // If age is more than 40 then credit risk 0.45
            if (!resp1?.filter { (it.item as User).age!! > 40 }?.all {
                    it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0).get("creditRisk") == 0.45
                }!!) {
                println("Invalid DMN: invalid 0.45 case \n${dmnTxt.body}")
                return false
            }
            // Else credit risk is 0
            if (!resp1?.filter { (it.item as User).age!! < 18 }?.all {
                    it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0).get("creditRisk") == 0
                }!!) {
                println("Invalid DMN: invalid 0 case \n${dmnTxt.body}")
                return false
            }
        } catch (e: Exception) {
            println("Invalid DMN: evaluation exception \n${e.message} \n$dmnTxt")
            return false
        }
        return true
    }


    fun test_experiment_2(): Boolean {
        assertThat(restTemplate).isNotNull();

        var dmnTxt: org.springframework.http.ResponseEntity<String>? = null
        try {
            val request = DmnGenerationRequest(
                """Hit policy is Rule Order
If car model year is more than 2000 then "text" is “Car after 2000s”
If car model year is less than 2000 (without 2000) then "text" is “Car before 2000”
If car make is “Porsche” then "carModel" is “Porsche”
If has no car then "text" is "No car"
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

            // If car model year is more than 2000 then text is “Car after 2000s”
            // If car make is “Porsche” then carModel is “Porsche”
            if (!resp1?.filter { (it.item as User).asset != null && (it.item as User).asset!!.modelYear!! > 2000 }?.all {
                    if ((it.item as User).asset!!.model!! == "Porsche") {
                        if (it.evaluation.resultList.size == 2) {
                            val isValid = it.evaluation.resultList.any { it.get("text") == "Car after 2000s" } &&
                                    it.evaluation.resultList.any { it.get("text") == "Porsche" }

                            if (!isValid) println("Invalid DMN: ${it.evaluation.resultList} ${(it.item as User).asset!!.model}")
                            return isValid
                        }
                        return false
                    } else {
                        if (it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0)
                                .get("text") == "Car after 2000s"
                        ) {
                            return true
                        }
                        println("Invalid DMN: ${it.evaluation.resultList} ${(it.item as User).asset!!.modelYear}")
                        return false
                    }
                }!!) {
                println("Invalid DMN: invalid 2000s case \n${dmnTxt.body}")
                return false
            }

            // If car model year is less than 2000 (without 2000) then text is “Car before 2000”
            // If car make is “Porsche” then carModel is “Porsche”
            if (!resp1?.filter { (it.item as User).asset != null && (it.item as User).asset!!.modelYear!! < 2000 }?.all {
                    if ((it.item as User).asset!!.model!! == "Porsche") {
                        if (it.evaluation.resultList.size == 2) {
                            val isValid = it.evaluation.resultList.any { it.get("text") == "Car before 2000" } &&
                                    it.evaluation.resultList.any { it.get("text") == "Porsche" }

                            if (!isValid) println("Invalid DMN: ${(it.item as User).asset!!.model}")
                            return isValid
                        }
                        return false
                    } else {
                        if (it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0)
                                .get("text") == "Car before 2000"
                        ) {
                            return true
                        }
                        println("Invalid DMN: ${(it.item as User).asset!!.modelYear}")
                        return false
                    }
                }!!) {
                println("Invalid DMN: invalid 2000s case \n${dmnTxt.body}")
                return false
            }

            // If has no car then text is "No car"
            if (!resp1?.filter { (it.item as User).asset == null }?.all {
                    it.evaluation.resultList.size == 1 && it.evaluation.resultList.get(0).get("text") == "No car"
                }!!) {
                println("Invalid DMN: invalid no car case \n${dmnTxt.body}")
                return false
            }

        } catch (e: Exception) {
            println("Invalid DMN: evaluation exception \n${e.message} \n$dmnTxt")
            return false
        }
        return true
    }

//    fun test_experiment_BMI(): Boolean {
//        assertThat(restTemplate).isNotNull();
//
//        var dmnTxt: org.springframework.http.ResponseEntity<String>? = null
//        try {
//            val request = DmnGenerationRequest(
//                """Hit policy is First
//The BMI of a person can be calculated based on weight in kgs and length of a person
//in meters by using the following formula: weight/(length*length)*10000 (type is double). If the BMI value is
//above 30, then the BMI-level is considered "obese". If you are a male and the BMI-value
//is under 18.5 then the BMI-level is "severely underweight" and if you are female, then
//you are considered "underweight" with the same bmi-value. If the BMI-value is between
//18.5 and 25 (without 25), then the BMI-level is "underweight" for a male and "normal" for
//a female. Lastly, if the BMI-value is between 25 and 30 and you are a Male then the
//BMI-level is "normal" but if you are a female then BMI-level is "overweight".
//Output: BMI_level
//                """.trimIndent(),
//                "bmi"
//            )
//            dmnTxt = restTemplate?.postForEntity(
//                "http://localhost:$port/api/anthropic-dmn/generate",
//                request,
//                String::class.java,
//            )
//            if (dmnTxt == null || dmnTxt.body == null || dmnTxt.body!!.isBlank()) {
//                println("❎Invalid DMN: empty response")
//                return false
//            }
//
//            val resp1: List<DmnItemResponse> =
//                decisionTableEvaluationService.evaluate(dmnTxt!!.body!!, "bmi") ?: return false
//
//            // If the BMI-value is above 30 then the BMI-level is considered Obese.
//            if (!resp1.filter { (it.item as Bmi).calculateBmi() > 30 }.all {
//                    if (it.evaluation.resultList.size != 1) {
//                        println("Invalid DMN: BMI-value is above 30 \n ${it.evaluation.resultList}\n${dmnTxt.body}")
//                        return false
//                    }
//                    val bmiLevel = (it.evaluation.resultList.get(0)
//                        .get("BMI_level") as String).lowercase(Locale.getDefault())
//                    if (bmiLevel != "obese") {
//                        println("Invalid DMN: BMI-value is above 30 \n ${it.evaluation.resultList}\n${dmnTxt.body}")
//                        return false
//                    }
//                    return true
//                }) {
//                println("Invalid DMN: invalid BMI-value is above 30 \n${dmnTxt.body}")
//                return false
//            }
//            // If gender is "Male" and the BMI-value is under 18.5 then the BMI-level is severely underweight
//            if (!resp1.filter { (it.item as Bmi).calculateBmi() < 18.5 && (it.item as Bmi).gender.lowercase() == "male" }
//                    .all {
//                        if (it.evaluation.resultList.size != 1) {
//                            println("Invalid DMN: Male BMI-value is under 18.5\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        val bmiLevel = (it.evaluation.resultList.get(0)
//                            .get("BMI_level") as String).lowercase(Locale.getDefault())
//                        if (bmiLevel != "underweight") {
//                            println("Invalid DMN: Male BMI-value is under 18.5\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        return true
//                    }) {
//                println("Invalid DMN: invalid Male BMI-value is under 18.5 \n${dmnTxt.body}")
//                return false
//            }
//            // if gender is "Female" and the BMI-value is under   18.5                     then the BMI-level is underweight.
//            if (!resp1.filter { (it.item as Bmi).calculateBmi() < 18.5 && (it.item as Bmi).gender.lowercase() == "female" }
//                    .all {
//                        if (it.evaluation.resultList.size != 1) {
//                            println("Invalid DMN: Female BMI-value is under 18.5\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        val bmiLevel = (it.evaluation.resultList.get(0)
//                            .get("BMI_level") as String).lowercase(Locale.getDefault())
//                        if (bmiLevel != "underweight") {
//                            println("Invalid DMN: Female BMI-value is under 18.5\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        return true
//                    }) {
//                println("Invalid DMN: invalid Female BMI-value is under 18.5 \n${dmnTxt.body}")
//                return false
//            }
//            // If gender is "Male"   and the BMI-value is between 18.5 and 25 (without 25) then the BMI-level is underweight
//            if (!resp1.filter { (it.item as Bmi).calculateBmi() >= 18.5 && (it.item as Bmi).calculateBmi() < 25 && (it.item as Bmi).gender.lowercase() == "male" }
//                    .all {
//                        if (it.evaluation.resultList.size != 1) {
//                            println("Invalid DMN: Male BMI-value is between 18.5 and 25 \n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        val bmiLevel = (it.evaluation.resultList.get(0)
//                            .get("BMI_level") as String).lowercase(Locale.getDefault())
//                        if (bmiLevel != "underweight") {
//                            println("Invalid DMN: Male BMI-value is between 18.5 and 25\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        return true
//                    }) {
//                println("Invalid DMN: invalid Male BMI-value is between 18.5 and 25 \n${dmnTxt.body}")
//                return false
//            }
//            // If gender is "Female" and the BMI-value is between 18.5 and 25 (without 25) then the BMI-level is normal.
//            if (!resp1.filter { (it.item as Bmi).calculateBmi() >= 18.5 && (it.item as Bmi).calculateBmi() < 25 && (it.item as Bmi).gender.lowercase() == "female" }
//                    .all {
//                        if (it.evaluation.resultList.size != 1) {
//                            println("Invalid DMN: Female BMI-value is between 18.5 and 25\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        val bmiLevel = (it.evaluation.resultList.get(0)
//                            .get("BMI_level") as String).lowercase(Locale.getDefault())
//                        if (bmiLevel != "normal") {
//                            println("Invalid DMN: Female BMI-value is between 18.5 and 25\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        return true
//                    }
//            ) {
//                println("Invalid DMN: invalid Female BMI-value is between 18.5 and 25 \n${dmnTxt.body}")
//                return false
//            }
//            // If gender is "Male"   and the BMI-value is between 25   and 30              then the BMI-level is normal
//            if (!resp1.filter { (it.item as Bmi).calculateBmi() in 25.0..30.0 && (it.item as Bmi).gender.lowercase() == "male" }
//                    .all {
//                        if (it.evaluation.resultList.size != 1) {
//                            println("Invalid DMN: Male BMI-value is between 25 and 30\n ${it.evaluation.resultList} \n${dmnTxt.body}")
//                            return false
//                        }
//                        val bmiLevel = (it.evaluation.resultList.get(0)
//                            .get("BMI_level") as String).lowercase(Locale.getDefault())
//                        if (bmiLevel != "normal") {
//                            println("Invalid DMN: Male BMI-value is between 25 and 30\n ${it.evaluation.resultList} \n${dmnTxt.body}")
//                            return false
//                        }
//                        return true
//                    }) {
//                println("Invalid DMN: invalid Male BMI-value is between 25 and 30 \n${dmnTxt.body}")
//                return false
//            }
//            // if gender is "Female" and the BMI-value is between 25   and 30              then the BMI-level is overweight.
//            if (!resp1.filter { (it.item as Bmi).calculateBmi() in 25.0..30.0 && (it.item as Bmi).gender.lowercase() == "female" }
//                    .all {
//                        if (it.evaluation.resultList.size != 1) {
//                            println("Invalid DMN: Female BMI-value is between 25 and 30\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        val bmiLevel = (it.evaluation.resultList.get(0)
//                            .get("BMI_level") as String).lowercase(Locale.getDefault())
//                        if (bmiLevel != "overweight") {
//                            println("Invalid DMN: Female BMI-value is between 25 and 30\n ${it.evaluation.resultList}")
//                            return false
//                        }
//                        return true
//                    }) {
//                println("Invalid DMN: invalid Female BMI-value is between 25 and 30 \n${dmnTxt.body}")
//                return false
//            }
//
//        } catch (e: Exception) {
//            println("Invalid DMN: evaluation exception \n${e.message} \n$dmnTxt")
//            return false
//        }
//        return true
//    }


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
