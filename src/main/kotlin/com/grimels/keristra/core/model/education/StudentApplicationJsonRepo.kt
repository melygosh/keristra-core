package com.grimels.keristra.core.model.education

import camundajar.impl.com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.grimels.keristra.core.model.bmi.Bmi
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.reflect.Type
import org.springframework.stereotype.Component
import org.springframework.util.ResourceUtils

@Component
class StudentApplicationJsonRepo {
    private var studentApplication: List<StudentApplication> = listOf()

    public fun getStudentApplicationList(): List<StudentApplication> {
        if (studentApplication.isEmpty()) {
            studentApplication = convertToModel();
        }
        return studentApplication
    }

    private fun convertToModel(): List<StudentApplication> {
        try {
            val stream = BufferedReader(getDataSet())
            val listType: Type = object : TypeToken<List<StudentApplication>>() {}.getType()
            val users: List<StudentApplication> = Gson().fromJson(stream, listType)

            return users
        } catch (ex: Exception) {
            throw IllegalArgumentException(ex.cause!!.message)
        }
    }

    private fun getDataSet(): FileReader {
        val file: File = ResourceUtils.getFile("classpath:data-set/education.json")
        return FileReader(file)
    }

}