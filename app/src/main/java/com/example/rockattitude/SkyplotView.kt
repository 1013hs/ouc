package com.example.rockattitude

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class SatInfo(
    val azimuth: Float,   // 方位角 0\~360
    val elevation: Float, // 仰角 0\~90
    val usedInFix: Boolean
)

class SkyplotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111")
        style = Paint.Style.FILL
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val usedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")  // 绿色 = 参与定位
        style = Paint.Style.FILL
    }
    private val unusedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA") // 灰色 = 未参与
        style = Paint.Style.FILL
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        strokeWidth = 1f
    }

    private var sats: List<SatInfo> = emptyList()

    fun setSatellites(list: List<SatInfo>) {
        sats = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 12f

        // 背景
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 仰角圈（0° 外圈，90° 圆心）
        for (i in 1..3) {
            val r = radius * i / 3f
            canvas.drawCircle(cx, cy, r, circlePaint)
        }

        // 十字线
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crossPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crossPaint)

        // 画卫星点
        for (sat in sats) {
            // 仰角 90° 在圆心，0° 在外圈
            val r = radius * (1f - sat.elevation / 90f)
            val azRad = Math.toRadians(sat.azimuth.toDouble())
            val x = cx + (r * sin(azRad)).toFloat()
            val y = cy - (r * cos(azRad)).toFloat()  // 北在上

            val paint = if (sat.usedInFix) usedPaint else unusedPaint
            canvas.drawCircle(x, y, 7f, paint)
        }
    }
}
