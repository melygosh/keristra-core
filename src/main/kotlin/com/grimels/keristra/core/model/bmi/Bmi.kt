package com.grimels.keristra.core.model.bmi

import com.fasterxml.jackson.annotation.JsonProperty

class Bmi(
    val id: Long,
    @JsonProperty("first_name")
    val firstName: String,
    @JsonProperty("last_name")
    val lastName: String,
    val email: String,
    val gender: String,
    val weight: Double,
    val length: Double
) {

    fun calculateBmi(): Double {
        return weight / (length * length) * 10000
    }
}
