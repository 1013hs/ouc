package com.example.rockattitude

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class TrackSession(
    val id: String,
    var name: String,
    val startTime: Long,
    var endTime: Long,
    val points: MutableList<TrackPoint>
) {
    val pointCount: Int get() = points.size
    fun timeRangeText(): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(startTime)) + " \~ " + fmt.format(Date(endTime))
    }
}

object TrackStorage {
    private const val PREF = "track_sessions_v2"
    private const val KEY_LIST = "sessions_json"
    private const val KEY_CURRENT = "current_session_id"

    fun loadAll(ctx: Context): MutableList<TrackSession> {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val result = mutableListOf<TrackSession>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pts = mutableListOf<TrackPoint>()
                val pa = o.optJSONArray("points") ?: JSONArray()
                for (j in 0 until pa.length()) {
                    val p = pa.getJSONObject(j)
                    pts.add(
                        TrackPoint(
                            p.getDouble("lat"),
                            p.getDouble("lng"),
                            p.optDouble("alt", 0.0),
                            p.optLong("t", 0L)
                        )
                    )
                }
                result.add(
                    TrackSession(
                        id = o.getString("id"),
                        name = o.optString("name", "轨迹"),
                        startTime = o.getLong("start"),
                        endTime = o.getLong("end"),
                        points = pts
                    )
                )
            }
        } catch (_: Exception) {}
        return result
    }

    fun saveAll(ctx: Context, list: List<TrackSession>) {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            o.put("start", s.startTime)
            o.put("end", s.endTime)
            val pa = JSONArray()
            for (p in s.points) {
                val po = JSONObject()
                po.put("lat", p.latitude)
                po.put("lng", p.longitude)
                po.put("alt", p.altitude)
                po.put("t", p.time)
                pa.put(po)
            }
            o.put("points", pa)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun upsert(ctx: Context, session: TrackSession) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == session.id }
        if (idx >= 0) list[idx] = session else list.add(0, session)
        // 最多保留 50 条历史
        while (list.size > 50) list.removeAt(list.size - 1)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = loadAll(ctx).filter { it.id != id }
        saveAll(ctx, list)
    }

    fun setCurrentId(ctx: Context, id: String?) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT, id).apply()
    }

    fun getCurrentId(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_CURRENT, null)
}
