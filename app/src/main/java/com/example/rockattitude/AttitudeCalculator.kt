package com.example.rockattitude

import kotlin.math.acos
import kotlin.math.atan2

data class Attitude(
    val strike: Float,
    val dip: Float,
    val dipDirection: Float
)

object AttitudeCalculator {
    /**
     * 手机背面贴在岩面上时使用
     * R 来自 SensorManager.getRotationMatrix
     */
    fun fromRotationMatrix(R: FloatArray): Attitude {
        // 设备 Z 轴在世界坐标系中的方向（法线）
        val nx = R[2]
        val ny = R[5]
        val nz = R[8]

        // 倾角 0\~90°
        val dip = Math.toDegrees(acos(nz.coerceIn(-1f, 1f).toDouble())).toFloat()
            .coerceIn(0f, 90f)

        // 倾向 0\~360°（正北为0，顺时针）
        var dipDirection = (Math.toDegrees(atan2(nx.toDouble(), ny.toDouble())) + 360) % 360

        // 走向（右手法则）= 倾向 - 90°
        val strike = ((dipDirection - 90) + 360) % 360

        return Attitude(strike.toFloat(), dip, dipDirection.toFloat())
    }
}
