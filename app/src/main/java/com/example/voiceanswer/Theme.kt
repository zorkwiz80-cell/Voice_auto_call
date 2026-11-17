package com.example.voiceanswer

import android.content.Context
import android.graphics.Color

/** Цвета всего телефона: акцент (синий, красный, радужный…) и фон (чёрный, тёмный, светлый). */
object Theme {

    class Accent(val id: String, val name: String, val color: Int)

    val accents = listOf(
        Accent("blue", "Синий", 0xFF3D7BFF.toInt()),
        Accent("red", "Красный", 0xFFFF3B47.toInt()),
        Accent("violet", "Фиолетовый", 0xFF9B5CFF.toInt()),
        Accent("green", "Зелёный", 0xFF2ECC71.toInt()),
        Accent("orange", "Оранжевый", 0xFFFF8A1F.toInt()),
        Accent("pink", "Розовый", 0xFFFF4FA3.toInt()),
        Accent("cyan", "Бирюзовый", 0xFF18C8D8.toInt()),
        Accent("rainbow", "Радужный", 0xFFFF3B47.toInt()),
        Accent("custom", "Свой цвет", 0xFF9B5CFF.toInt())
    )

    val rainbowColors = intArrayOf(
        0xFFFF3B47.toInt(), 0xFFFF8A1F.toInt(), 0xFFFFD60A.toInt(), 0xFF2ECC71.toInt(),
        0xFF18C8D8.toInt(), 0xFF3D7BFF.toInt(), 0xFF9B5CFF.toInt()
    )

    val GREEN = 0xFF34C759.toInt()
    val RED = 0xFFFF453A.toInt()

    var accentId = "blue"
    var hue = 280
    var bgMode = "black"

    var bg = 0xFF000000.toInt()
    var card = 0xFF141416.toInt()
    var card2 = 0xFF1F1F23.toInt()
    var text = 0xFFFFFFFF.toInt()
    var sub = 0xFF8E8E93.toInt()
    var line = 0x22FFFFFF
    var light = false

    val rainbow: Boolean get() = accentId == "rainbow"

    /** Текущий акцентный цвет. У радужного темы меняется со временем. */
    val accent: Int
        get() = when (accentId) {
            "rainbow" -> Color.HSVToColor(floatArrayOf(((System.currentTimeMillis() / 25) % 360).toFloat(), 0.75f, 1f))
            "custom" -> Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.78f, 1f))
            else -> accents.firstOrNull { it.id == accentId }?.color ?: accents[0].color
        }

    fun load(c: Context) {
        accentId = Prefs.accentId(c)
        hue = Prefs.hue(c)
        bgMode = Prefs.bgMode(c)
        when (bgMode) {
            "light" -> {
                light = true
                bg = 0xFFF2F3F7.toInt(); card = 0xFFFFFFFF.toInt(); card2 = 0xFFE6E8EF.toInt()
                text = 0xFF111114.toInt(); sub = 0xFF6B6F7B.toInt(); line = 0x22000000
            }
            "dark" -> {
                light = false
                bg = 0xFF11131A.toInt(); card = 0xFF1B1E27.toInt(); card2 = 0xFF272B37.toInt()
                text = 0xFFFFFFFF.toInt(); sub = 0xFF9AA0B0.toInt(); line = 0x22FFFFFF
            }
            else -> {
                light = false
                bg = 0xFF000000.toInt(); card = 0xFF141416.toInt(); card2 = 0xFF1F1F23.toInt()
                text = 0xFFFFFFFF.toInt(); sub = 0xFF8E8E93.toInt(); line = 0x22FFFFFF
            }
        }
    }

    fun signature(): String = "$accentId/$hue/$bgMode"

    /** Смешивает два цвета: t = 0 → a, t = 1 → b. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    /** Цвет аватарки для имени — один и тот же имя → один и тот же цвет. */
    fun avatarColor(key: String?): Int {
        val palette = intArrayOf(
            0xFF8E7CFF.toInt(), 0xFF3D9BFF.toInt(), 0xFF6C7CFF.toInt(), 0xFFB07CFF.toInt(),
            0xFF2FB9A3.toInt(), 0xFFFF7A59.toInt(), 0xFFE86AA6.toInt(), 0xFF4CAF50.toInt()
        )
        val h = (key ?: "").hashCode()
        return palette[((h % palette.size) + palette.size) % palette.size]
    }
}
