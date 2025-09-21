package com.grimels.keristra.core.model.user

data class User(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val gender: String,
    val age: Int,
    val hasSubsidies: Boolean,
    val asset: Asset?
)

data class Asset(
    val make: String,
    val model: String,
    val modelYear: Int,
    val segment: Segment
)

data class Segment(
    val name: String,
    val code: String
)