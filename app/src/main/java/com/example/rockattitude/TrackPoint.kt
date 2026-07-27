package com.example.rockattitude

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val time: Long = System.currentTimeMillis()
)
