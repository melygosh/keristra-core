package com.grimels.keristra.core.model

data class HotelRoom(val number: Int, val price: Double, val quantity: Int, val rate: Double)

data class Hotel(val name: String, val rooms: List<HotelRoom>)