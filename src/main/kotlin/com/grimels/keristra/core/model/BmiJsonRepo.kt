package com.grimels.keristra.core.model

import camundajar.impl.com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.reflect.Type
import org.springframework.stereotype.Component
import org.springframework.util.ResourceUtils

@Component
class BmiJsonRepo {
    private var bmiList: List<Bmi> = listOf()

    public fun getBmiList(): List<Bmi> {
        if (bmiList.isEmpty()) {
            bmiList = convertToModel();
        }
        return bmiList
    }

    private fun convertToModel(): List<Bmi> {
        try {
            val stream = BufferedReader(getDataSet())
            val listType: Type = object : TypeToken<List<Bmi>>() {}.getType()
            val users: List<Bmi> = Gson().fromJson(stream, listType)

            return users
        } catch (ex: Exception) {
            throw IllegalArgumentException(ex.cause!!.message)
        }
    }

    private fun getDataSet(): FileReader {
        val file: File = ResourceUtils.getFile("classpath:data-set/data-set-3.json")
        return FileReader(file)
    }

}