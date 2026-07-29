package com.example.rockattitude

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class RoseStereonetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strikes = mutableListOf<Float>()
    private val dips = mutableListOf<Float>()
    private val dipDirs = mutableListOf<Float>()

    private val paintCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintRose = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.FILL
        alpha = 180
    }
    private val paintPole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        style = Paint.Style.FILL
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFD8DC")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun setData(records: List<Record>) {
        strikes.clear()
        dips.clear()
        dipDirs.clear()
        for (r in records) {
            strikes.add(r.strike)
            dips.add(r.dip)
            dipDirs.add(r.dipDirection)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 40f

        // 背景圆
        canvas.drawCircle(cx, cy, radius, paintCircle)

        // 网格
        for (i in 1..3) {
            canvas.drawCircle(cx, cy, radius * i / 3f, paintGrid)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paintGrid)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paintGrid)

        if (strikes.isEmpty()) {
            canvas.drawText("暂无数据", cx, cy, paintText)
            return
        }

        // ===== 玫瑰花图（走向） =====
        val binSize = 10
        val bins = IntArray(36)
        for (s in strikes) {
            val bin = ((s % 360) / binSize).toInt().coerceIn(0, 35)
            bins[bin]++
        }
        val maxCount = bins.maxOrNull()?.coerceAtLeast(1) ?: 1

        val path = Path()
        for (i in 0 until 36) {
            val count = bins[i]
            if (count == 0) continue
            val r = radius * 0.85f * count / maxCount
            val startAngle = i * binSize - 90f
            val sweep = binSize.toFloat()

            path.reset()
            path.moveTo(cx, cy)
            path.arcTo(cx - r, cy - r, cx + r, cy + r, startAngle, sweep, false)
            path.close()
            canvas.drawPath(path, paintRose)
        }

        // ===== 赤平投影极点（等面积近似） =====
        for (i in dips.indices) {
            val dip = dips[i]
            val dipDir = dipDirs[i]
            // 修复类型：全部转为 Float
            val r = (radius * sqrt(2.0) * sin(Math.toRadians(dip / 2.0))).toFloat()
            val azRad = Math.toRadians(dipDir.toDouble())
            val x = cx + r * sin(azRad).toFloat()
            val y = cy - r * cos(azRad).toFloat()
            canvas.drawCircle(x, y, 8f, paintPole)
        }

        // 标注
        canvas.drawText("N", cx, cy - radius - 10, paintText)
        canvas.drawText("玫瑰=走向  红点=极点", cx, height - 20f, paintText)
    }
}
