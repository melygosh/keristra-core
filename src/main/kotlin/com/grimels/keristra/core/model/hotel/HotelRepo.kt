package com.grimels.keristra.core.model.hotel

import camundajar.impl.com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.reflect.Type
import org.springframework.stereotype.Component
import org.springframework.util.ResourceUtils

@Component
class HotelRepo {
    private var hotels: List<Hotel> = listOf()

    public fun getHotels(): List<Hotel> {
        if (hotels.isEmpty()) {
            hotels = convertToModel();
        }
        return hotels
    }

    private fun convertToModel(): List<Hotel> {
        try {
            val stream = BufferedReader(getDataSet())
            val listType: Type = object : com.google.gson.reflect.TypeToken<List<Hotel>>() {}.getType()
            val hotels: List<Hotel> = Gson().fromJson(stream, listType)

            return hotels
        } catch (ex: Exception) {
            throw IllegalArgumentException(ex.cause!!.message)
        }
    }

    private fun getDataSet(): FileReader {
        val file: File = ResourceUtils.getFile("classpath:data-set/hotel.json")
        return FileReader(file)
    }
}