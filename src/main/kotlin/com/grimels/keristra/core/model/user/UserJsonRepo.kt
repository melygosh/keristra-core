package com.grimels.keristra.core.model.user

import camundajar.impl.com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.reflect.Type
import org.springframework.stereotype.Component
import org.springframework.util.ResourceUtils

@Component
class UserJsonRepo {
    private var users: List<User> = listOf()

    public fun getUsers(): List<User> {
        if (users.isEmpty()) {
            users = convertToModel();
        }
        return users
    }

    private fun convertToModel(): List<User> {
        try {
            val stream = BufferedReader(getDataSet())
            val listType: Type = object : com.google.gson.reflect.TypeToken<List<User>>() {}.getType()
            val users: List<User> = Gson().fromJson(stream, listType)

            return users
        } catch (ex: Exception) {
            throw IllegalArgumentException(ex.cause!!.message)
        }
    }

    private fun getDataSet(): FileReader {
        val file: File = ResourceUtils.getFile("classpath:data-set/user.json")
        return FileReader(file)
    }

}