package com.example.rockattitude

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class BoreholeColumnView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Layer(val name: String, val from: Float, val to: Float, val color: Int)

    private val layers = mutableListOf<Layer>()
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 28f
    }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setLayers(list: List<Layer>) {
        layers.clear()
        layers.addAll(list)
        invalidate()
    }

    fun getLayers(): List<Layer> = layers.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 白底，方便导出
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        if (layers.isEmpty()) {
            canvas.drawText("暂无层位数据", 40f, 80f, paintText)
            return
        }

        val maxDepth = layers.maxOf { it.to }.coerceAtLeast(1f)
        val left = 80f
        val right = width - 40f
        val top = 40f
        val bottom = height - 40f
        val h = bottom - top

        layers.forEach { layer ->
            val y1 = top + h * (layer.from / maxDepth)
            val y2 = top + h * (layer.to / maxDepth)
            paintFill.color = layer.color
            canvas.drawRect(left, y1, right, y2, paintFill)
            canvas.drawRect(left, y1, right, y2, paintLine)

            val label = layer.name + " " + layer.from.toInt() + "-" + layer.to.toInt() + "m"
            canvas.drawText(label, left + 12f, (y1 + y2) / 2 + 10f, paintText)
        }

        canvas.drawText("0m", 10f, top + 10f, paintText)
        canvas.drawText(maxDepth.toInt().toString() + "m", 10f, bottom, paintText)
    }
}
