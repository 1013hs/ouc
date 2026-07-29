package com.example.rockattitude

import android.content.Context

object LithologyStorage {
    private const val PREF = "lithology_lib"
    private const val KEY = "list"

    fun load(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY, "") ?: ""
        return if (str.isBlank()) mutableListOf() else str.split("|").filter { it.isNotBlank() }.toMutableList()
    }

    fun save(context: Context, list: List<String>) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, list.joinToString("|")).apply()
    }
}
