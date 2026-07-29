package com.example.rockattitude

import android.content.Context
import kotlin.math.*

object CoordHelper {
    private const val PREF = "coord_pref"
    private const val KEY_MODE = "mode"          // 0=经纬度  1=公里网
    private const val KEY_ZONE = "zone"           // 带号，默认 50

    fun isLatLng(context: Context): Boolean {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_MODE, 0) == 0
    }

    fun setMode(context: Context, latLng: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, if (latLng) 0 else 1).apply()
    }

    fun getZone(context: Context): Int {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_ZONE, 50)
    }

    fun setZone(context: Context, zone: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ZONE, zone).apply()
    }

    // 简单高斯投影（近似，适用于大多数野外）
    fun toGaussKruger(lat: Double, lng: Double, zone: Int): Pair<Double, Double> {
        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val e2 = 2 * f - f * f
        val lon0 = (zone * 6 - 3).toDouble()  // 中央经线

        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lng - lon0)

        val N = a / sqrt(1 - e2 * sin(latRad).pow(2))
        val t = tan(latRad)
        val c = e2 * cos(latRad).pow(2) / (1 - e2)
        val A = cos(latRad) * lonRad

        val M = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64) * latRad
                - (3 * e2 / 8 + 3 * e2 * e2 / 32) * sin(2 * latRad)
                + (15 * e2 * e2 / 256) * sin(4 * latRad))

        val x = N * (A + (1 - t * t + c) * A.pow(3) / 6)
        val y = M + N * t * (A * A / 2 + (5 - t * t + 9 * c + 4 * c * c) * A.pow(4) / 24)

        val easting = x + 500000.0
        val northing = y
        return Pair(easting, northing)
    }
}
