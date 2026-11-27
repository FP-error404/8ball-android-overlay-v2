package com.poolpredictor2

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/**
 * Mengelola tema UI overlay secara dinamis dari file JSON (assets/themes.json).
 * Tidak ada warna UI yang di-hardcode di dalam View/Service.
 */
class ThemeManager(private val context: Context) {

    data class OverlayTheme(
        val id: String,
        val name: String,
        val background: Int,
        val panel: Int,
        val accent: Int,
        val highlight: Int,
        val textPrimary: Int,
        val textSecondary: Int
    )

    private val themesMap = mutableMapOf<String, OverlayTheme>()
    var currentTheme: OverlayTheme
        private set

    init {
        loadFromAssets("themes.json")
        currentTheme = themesMap.values.firstOrNull() ?: createFallbackTheme()
    }

    fun loadFromAssets(assetFileName: String) {
        val jsonString = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
        parseJson(jsonString)
    }

    fun parseJson(jsonString: String) {
        val root = JSONObject(jsonString)
        val activeId = root.optString("active_theme", "blood_moon")
        val themesObj = root.getJSONObject("themes")

        themesMap.clear()
        val keys = themesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = themesObj.getJSONObject(key)
            val theme = OverlayTheme(
                id = key,
                name = item.getString("name"),
                background = Color.parseColor(item.getString("background")),
                panel = Color.parseColor(item.getString("panel")),
                accent = Color.parseColor(item.getString("accent")),
                highlight = Color.parseColor(item.getString("highlight")),
                textPrimary = Color.parseColor(item.optString("text_primary", "#FFFFFF")),
                textSecondary = Color.parseColor(item.optString("text_secondary", "#CCCCCC"))
            )
            themesMap[key] = theme
        }

        themesMap[activeId]?.let {
            currentTheme = it
        }
    }

    fun switchTheme(themeId: String): Boolean {
        val selected = themesMap[themeId] ?: return false
        currentTheme = selected
        return true
    }

    fun getAvailableThemes(): List<OverlayTheme> = themesMap.values.toList()

    private fun createFallbackTheme(): OverlayTheme {
        return OverlayTheme(
            id = "blood_moon",
            name = "Blood Moon",
            background = Color.parseColor("#0B080A"),
            panel = Color.parseColor("#171014"),
            accent = Color.parseColor("#B00020"),
            highlight = Color.parseColor("#FF1744"),
            textPrimary = Color.parseColor("#F5EEF0"),
            textSecondary = Color.parseColor("#A8999E")
        )
    }
}
