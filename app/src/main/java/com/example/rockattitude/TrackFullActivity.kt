package com.example.rockattitude

import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.*

object SharedTrackData {
    var points: List<TrackPoint> = emptyList()
    var altHistory: MutableList<Pair<Long, Float>> = mutableListOf()
    var pressHistory: MutableList<Pair<Long, Float>> = mutableListOf()
}

class TrackFullActivity : AppCompatActivity() {

    private lateinit var chartView: DualChartView
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val btnBack = Button(this).apply {
            text = "← 返回主页"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(24, 24, 0, 12)
        root.addView(btnBack, lp)

        chartView = DualChartView(this)
        root.addView(chartView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        startUpdate()
    }

    private fun startUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                chartView.setData(
                    SharedTrackData.altHistory.toList(),
                    SharedTrackData.pressHistory.toList(),
                    SharedTrackData.points
                )
                handler.postDelayed(this, 1000)
            }
        })
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

class DualChartView(context: android.content.Context) : android.view.View(context) {

    private var alts = listOf<Pair<Long, Float>>()
    private var presses = listOf<Pair<Long, Float>>()
    private var tracks = listOf<TrackPoint>()

    private val paintAlt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val paintPress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f
    }

    fun setData(a: List<Pair<Long, Float>>, p: List<Pair<Long, Float>>, t: List<TrackPoint>) {
        alts = a
        presses = p
        tracks = t
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h * 0.55f

        canvas.drawText("海拔(绿) / 气压(蓝) 动态曲线", 30f, 50f, paintText)
        canvas.drawText("轨迹曲线", 30f, mid + 40f, paintText)
        canvas.drawLine(0f, mid, w, mid, paintGrid)

        if (alts.size > 1) {
            val minA = alts.minOf { it.second }
            val maxA = alts.maxOf { it.second }.coerceAtLeast(minA + 1f)
            val path = Path()
            alts.forEachIndexed { i, pair ->
                val x = 40f + (w - 80f) * i / (alts.size - 1)
                val y = 80f + (mid - 120f) * (1 - (pair.second - minA) / (maxA - minA))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paintAlt)
        }

        if (presses.size > 1) {
            val minP = presses.minOf { it.second }
            val maxP = presses.maxOf { it.second }.coerceAtLeast(minP + 1f)
            val path = Path()
            presses.forEachIndexed { i, pair ->
                val x = 40f + (w - 80f) * i / (presses.size - 1)
                val y = 80f + (mid - 120f) * (1 - (pair.second - minP) / (maxP - minP))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paintPress)
        }

        if (tracks.size > 1) {
            val minLat = tracks.minOf { it.latitude }
            val maxLat = tracks.maxOf { it.latitude }.coerceAtLeast(minLat + 1e-6)
            val minLng = tracks.minOf { it.longitude }
            val maxLng = tracks.maxOf { it.longitude }.coerceAtLeast(minLng + 1e-6)
            val path = Path()
            tracks.forEachIndexed { i, p ->
                val x = 40f + (w - 80f) * ((p.longitude - minLng) / (maxLng - minLng)).toFloat()
                val y = mid + 60f + (h - mid - 100f) * (1 - ((p.latitude - minLat) / (maxLat - minLat)).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paintTrack)
        }
    }
}
