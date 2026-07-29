package com.example.rockattitude

import kotlin.math.*

data class Attitude(
    val strike: Float,
    val dip: Float,
    val dipDirection: Float
)

object AttitudeCalculator {

    /**
     * 从旋转矩阵计算岩层产状
     * 兼容屏幕朝上和屏幕朝下两种情况
     */
    fun fromRotationMatrix(R: FloatArray): Attitude {
        // R 是 3x3 行主序旋转矩阵（SensorManager.getRotationMatrix 返回）
        // 设备坐标系：X右、Y上、Z出屏幕

        // 取设备 Z 轴在世界坐标系中的方向作为平面法向量
        val nx = R[2]   // R[0][2]
        val ny = R[5]   // R[1][2]
        val nz = R[8]   // R[2][2]

        // 倾角 = 法向量与竖直方向（世界Z）夹角
        // 使用绝对值，保证屏幕朝上/朝下都能得到正确倾角（0\~90°）
        var dip = Math.toDegrees(acos(nz.toDouble().coerceIn(-1.0, 1.0))).toFloat()
        if (dip > 90f) dip = 180f - dip          // 规范化到 0\~90

        // 倾向（dip direction）：法向量在水平面的投影方向
        // atan2(-nx, -ny) 使北为0、顺时针增加，并处理正反面
        var dipDir = Math.toDegrees(atan2((-nx).toDouble(), (-ny).toDouble())).toFloat()
        dipDir = (dipDir + 360f) % 360f

        // 当倾角接近0或90时，倾向不稳定，做保护
        if (dip < 0.5f || dip > 89.5f) {
            // 几乎水平或竖直时，用磁北方向辅助
            dipDir = (dipDir + 360f) % 360f
        }

        // 走向 = 倾向 - 90°（右手法则）
        var strike = (dipDir - 90f + 360f) % 360f

        // 再次规范化
        if (dip < 0f) dip = 0f
        if (dip > 90f) dip = 90f

        return Attitude(strike, dip, dipDir)
    }

    /**
     * 备用方法：直接用加速度计重力向量计算（更抗翻转）
     */
    fun fromGravity(gravity: FloatArray, geomagnetic: FloatArray? = null): Attitude {
        val gx = gravity[0]
        val gy = gravity[1]
        val gz = gravity[2]
        val gNorm = sqrt(gx*gx + gy*gy + gz*gz)
        if (gNorm < 1e-3f) return Attitude(0f, 0f, 0f)

        // 归一化重力（指向地心）
        val nx = gx / gNorm
        val ny = gy / gNorm
        val nz = gz / gNorm

        // 倾角：手机平面与水平面夹角
        // |nz| 接近1 → 水平（倾角0），接近0 → 竖直（倾角90）
        var dip = Math.toDegrees(acos(abs(nz).toDouble().coerceIn(0.0, 1.0))).toFloat()
        // 上面公式其实是 90 - 与竖直夹角，调整为真正倾角
        dip = 90f - Math.toDegrees(asin(abs(nz).toDouble().coerceIn(0.0, 1.0))).toFloat()
        dip = dip.coerceIn(0f, 90f)

        // 倾向
        var dipDir = Math.toDegrees(atan2((-nx).toDouble(), (-ny).toDouble())).toFloat()
        dipDir = (dipDir + 360f) % 360f

        val strike = (dipDir - 90f + 360f) % 360f

        return Attitude(strike, dip, dipDir)
    }
}
