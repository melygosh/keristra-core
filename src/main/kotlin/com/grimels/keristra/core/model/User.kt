package com.grimels.keristra.core.model

import com.fasterxml.jackson.annotation.JsonProperty

class User {
    var id: Int? = null
    var firstName: String? = null
    var lastName: String? = null
    var email: String? = null
    val gender: String? = null
    var age: Int? = null
    val language: String? = null
    val university: String? = null
    val car: Car? = null
    val pet: String? = null
    val phoneOS: String? = null
    val comments: MutableList<UserComment> = mutableListOf()
}