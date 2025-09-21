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
class VapJsonRepo {
    private var vapList: List<Vap> = listOf()

    public fun getBmiList(): List<Vap> {
        if (vapList.isEmpty()) {
            vapList = convertToModel();
        }
        return vapList
    }

    private fun convertToModel(): List<Vap> {
        try {
            val stream = BufferedReader(getDataSet())
            val listType: Type = object : TypeToken<List<Vap>>() {}.getType()
            val users: List<Vap> = Gson().fromJson(stream, listType)

            return users
        } catch (ex: Exception) {
            throw IllegalArgumentException(ex.cause!!.message)
        }
    }

    private fun getDataSet(): FileReader {
        val file: File = ResourceUtils.getFile("classpath:data-set/data-set-4.json")
        return FileReader(file)
    }

}