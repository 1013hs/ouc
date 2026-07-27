package com.example.rockattitude

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class TrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 28f
    }

    private var points: List<TrackPoint> = emptyList()
    private var current: TrackPoint? = null
    private var target: TrackPoint? = null

    fun updateTrack(track: List<TrackPoint>, currentPos: TrackPoint?, targetPos: TrackPoint?) {
        points = track
        current = currentPos
        target = targetPos
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty() && current == null) {
            canvas.drawText("暂无轨迹数据", width / 2f - 80f, height / 2f, textPaint)
            return
        }

        val all = mutableListOf<TrackPoint>()
        all.addAll(points)
        current?.let { all.add(it) }
        target?.let { all.add(it) }

        if (all.isEmpty()) return

        val minLat = all.minOf { it.latitude }
        val maxLat = all.maxOf { it.latitude }
        val minLng = all.minOf { it.longitude }
        val maxLng = all.maxOf { it.longitude }

        val latRange = (maxLat - minLat).coerceAtLeast(0.0001)
        val lngRange = (maxLng - minLng).coerceAtLeast(0.0001)

        val padding = 40f
        val scaleX = (width - 2 * padding) / lngRange
        val scaleY = (height - 2 * padding) / latRange
        val scale = min(scaleX, scaleY)

        fun toX(lng: Double) = padding + ((lng - minLng) * scale).toFloat()
        fun toY(lat: Double) = height - padding - ((lat - minLat) * scale).toFloat()

        // 画轨迹线
        if (points.size >= 2) {
            val path = Path()
            path.moveTo(toX(points[0].longitude), toY(points[0].latitude))
            for (i in 1 until points.size) {
                path.lineTo(toX(points[i].longitude), toY(points[i].latitude))
            }
            canvas.drawPath(path, trackPaint)
        }

        // 画轨迹点
        points.forEach {
            canvas.drawCircle(toX(it.longitude), toY(it.latitude), 8f, trackPaint)
        }

        // 当前点
        current?.let {
            canvas.drawCircle(toX(it.longitude), toY(it.latitude), 14f, currentPaint)
        }

        // 目标点
        target?.let {
            canvas.drawCircle(toX(it.longitude), toY(it.latitude), 14f, targetPaint)
        }
    }
}
