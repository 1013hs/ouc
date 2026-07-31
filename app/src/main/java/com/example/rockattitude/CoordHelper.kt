package com.example.rockattitude

import android.content.Context
import kotlin.math.*

object CoordHelper {

    private const val PREF = "coord_settings"
    private const val KEY_MODE = "is_latlng"      // true=经纬度(CGCS2000), false=公里网
    private const val KEY_ZONE = "zone"           // 3度带带号
    private const val KEY_DATUM = "datum"         // CGCS2000 / WGS84 等（显示用）

    // CGCS2000 椭球参数
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257222101
    private const val B = A * (1 - F)
    private const val E2 = (A * A - B * B) / (A * A)
    private const val EP2 = (A * A - B * B) / (B * B)

    fun isLatLng(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_MODE, true)

    fun setMode(ctx: Context, latLng: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_MODE, latLng).apply()
    }

    fun getZone(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_ZONE, 34)

    fun setZone(ctx: Context, zone: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_ZONE, zone).apply()
    }

    fun getDatum(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_DATUM, "CGCS2000") ?: "CGCS2000"

    fun setDatum(ctx: Context, datum: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_DATUM, datum).apply()
    }

    /**
     * 高斯-克吕格 3度带投影（CGCS2000）
     * 返回 Pair(北向X, 东向Y)
     * X：北向坐标，约 7 位（百万米级，如 2992046）
     * Y：东向坐标，带号+假东 + 实际东向，约 8 位（如 34732184，即 34 带）
     */
    fun toGaussKruger(lat: Double, lng: Double, zone: Int): Pair<Double, Double> {
        val L0 = Math.toRadians(zone * 3.0)          // 中央子午线
        val B = Math.toRadians(lat)
        val l = Math.toRadians(lng) - L0

        val sinB = sin(B)
        val cosB = cos(B)
        val tanB = tan(B)
        val N = A / sqrt(1 - E2 * sinB * sinB)
        val t = tanB * tanB
        val C = EP2 * cosB * cosB
        val A1 = cosB * l
        val M = A * (
            (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * B
            - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * sin(2 * B)
            + (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * sin(4 * B)
            - (35 * E2 * E2 * E2 / 3072) * sin(6 * B)
        )

        val x = M + N * tanB * (
            A1 * A1 / 2
            + (5 - t + 9 * C + 4 * C * C) * A1 * A1 * A1 * A1 / 24
            + (61 - 58 * t + t * t + 270 * C - 330 * t * C) * A1.pow(6) / 720
        )
        val y = N * (
            A1
            + (1 - t + C) * A1 * A1 * A1 / 6
            + (5 - 18 * t + t * t + 14 * C - 58 * t * C) * A1.pow(5) / 120
        ) + 500000.0

        // 东向加上带号*1000000，形成 8 位显示（如 34732184）
        val yWithZone = zone * 1_000_000.0 + y
        return Pair(x, yWithZone)
    }
}
