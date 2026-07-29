package com.example.rockattitude

data class Record(
    var strike: Float = 0f,
    var dip: Float = 0f,
    var dipDirection: Float = 0f,
    var time: String = "",
    var note: String = "",
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var altitude: Double = 0.0,
    var lithology: String = ""          // 岩性
)
