package com.example.rockattitude

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object RecordStorage {
    private const val PREF_NAME = "rock_attitude_records"
    private const val KEY = "records"
    private val gson = Gson()

    fun load(context: Context): MutableList<Record> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Record>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun save(context: Context, list: List<Record>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }
}
