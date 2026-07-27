package com.example.rockattitude

data class Record(
    val id: Long = System.currentTimeMillis(),
    var strike: Float,
    var dip: Float,
    var dipDirection: Float,
    var note: String = "",
    val time: String
)
