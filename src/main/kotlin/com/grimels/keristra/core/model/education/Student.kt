package com.grimels.keristra.core.model.education

class Student(
    val id: String,
    val firstName: String,
    val lastName: String,
    val age: Int,
) {
}

class SubjectScore(
    val name: String,
    val score: Double,) {
}

class StudentApplication(
    val student: Student,
    val K1: SubjectScore,
    val K2: SubjectScore,
    val K3: SubjectScore,
    val K4_1: SubjectScore,
    val K4_2: SubjectScore,
    val K4_3: SubjectScore,
    val K4_4: SubjectScore,
    val K4_5: SubjectScore,
    val K4_6: SubjectScore,
)