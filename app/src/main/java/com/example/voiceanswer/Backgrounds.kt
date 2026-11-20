@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import java.util.Random
import kotlin.math.max
import kotlin.math.sin

/** Фоновые темы приложения: новогодняя, зимняя, летняя, весенняя, осенняя и своя картинка. */
object Backgrounds {

    class Opt(val id: String, val name: String)

    val options = listOf(
        Opt("none", "Без темы"),
        Opt("newyear", "Новогодняя"),
        Opt("winter", "Зимняя"),
        Opt("summer", "Летняя"),
        Opt("spring", "Весенняя"),
        Opt("autumn", "Осенняя"),
        Opt("xbox", "X-Box"),
        Opt("dragon", "Red Dragon"),
        Opt("custom", "Своя картинка")
    )

    private fun customFile(c: Context) = File(c.applicationContext.filesDir, "custom_bg.jpg")

    fun hasCustom(c: Context): Boolean = customFile(c).exists()

    private var cachedVer = -1L
    private var cachedBmp: Bitmap? = null

    private fun loadCustom(c: Context): Bitmap? {
        val ver = Prefs.customBgVersion(c)
        if (ver == cachedVer && cachedBmp != null) return cachedBmp
        val f = customFile(c)
        if (!f.exists()) return null
        val bmp = try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        cachedBmp = bmp
        cachedVer = ver
        return bmp
    }

    /** Рисунок фона для темы; null — без темы (или своя картинка ещё не выбрана). */
    fun drawable(c: Context, id: String): Drawable? = when (id) {
        "newyear", "winter", "summer", "spring", "autumn", "xbox", "dragon" -> SeasonDrawable(id)
        "custom" -> loadCustom(c)?.let { CustomDrawable(it) }
        else -> null
    }

    /** Сохраняет выбранную картинку (уменьшив её) как фон. Возвращает true при успехе. */
    fun saveCustom(c: Context, uri: Uri): Boolean {
        val app = c.applicationContext
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            val o = BitmapFactory.Options()
            o.inSampleSize = sample
            val bmp = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return false
            val tmp = File(app.filesDir, "custom_bg.tmp")
            tmp.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            val dst = customFile(app)
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) return false
            Prefs.setCustomBgVersion(app, System.currentTimeMillis())
            cachedBmp = null
            cachedVer = -1L
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }
}

/** Своя картинка: растягивается на весь экран без искажений и слегка затемняется. */
class CustomDrawable(private val bmp: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()

    init {
        scrim.color = 0x66000000
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val scale = max(w / bmp.width, h / bmp.height)
        val dx = (w - bmp.width * scale) / 2f
        val dy = (h - bmp.height * scale) / 2f
        canvas.save()
        canvas.clipRect(b)
        canvas.translate(b.left + dx, b.top + dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restore()
        canvas.drawRect(b, scrim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Сезонные фоны, нарисованные кодом: градиент и узор (снежинки, листья, лепестки, солнце, гирлянда). */
class SeasonDrawable(private val kind: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.clipRect(b)
        canvas.translate(b.left.toFloat(), b.top.toFloat())

        val top: Int
        val bottom: Int
        when (kind) {
            "newyear" -> { top = 0xFF4A0D14.toInt(); bottom = 0xFF0B3B26.toInt() }
            "winter" -> { top = 0xFF0E2A47.toInt(); bottom = 0xFF4F86B8.toInt() }
            "summer" -> { top = 0xFF1E78D2.toInt(); bottom = 0xFFF2C25B.toInt() }
            "spring" -> { top = 0xFF2F6B4F.toInt(); bottom = 0xFFD98BB0.toInt() }
            "xbox" -> { top = 0xFF030603.toInt(); bottom = 0xFF0A4A1A.toInt() }
            "dragon" -> { top = 0xFF0B0B0C.toInt(); bottom = 0xFF050506.toInt() }
            else -> { top = 0xFF4A2410.toInt(); bottom = 0xFFD9822B.toInt() }
        }
        paint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        when (kind) {
            "newyear" -> {
                snow(canvas, w, h, 70)
                garland(canvas, w, h)
            }
            "winter" -> {
                snow(canvas, w, h, 110)
                paint.color = 0xCCFFFFFF.toInt()
                rect.set(-w * 0.2f, h * 0.94f, w * 0.7f, h * 1.1f)
                canvas.drawOval(rect, paint)
                rect.set(w * 0.35f, h * 0.96f, w * 1.2f, h * 1.12f)
                canvas.drawOval(rect, paint)
            }
            "summer" -> {
                sun(canvas, w, h)
                cloud(canvas, w * 0.22f, h * 0.18f, w * 0.12f)
                cloud(canvas, w * 0.55f, h * 0.34f, w * 0.09f)
                waves(canvas, w, h)
            }
            "xbox" -> xbox(canvas, w, h)
            "dragon" -> dragon(canvas, w, h)
            "spring" -> leaves(canvas, w, h, 40, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFC1D9.toInt(), 0xFFFF8FB8.toInt()), 0.016f)
            else -> leaves(canvas, w, h, 34, intArrayOf(0xFFFF7A1A.toInt(), 0xFFD7263D.toInt(), 0xFFFFC53D.toInt(), 0xFF8B4513.toInt()), 0.03f)
        }
        canvas.restore()
    }

    /** X-Box: чёрный фон, зелёное свечение и сетка из шестиугольников (без логотипов). */
    private fun xbox(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.78f
        val cy = h * 0.2f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, w * 0.75f, 0x9937E04F.toInt(), 0x0037E04F, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.003f
        paint.color = 0x2E6BFF7C
        val r = w * 0.11f
        val dx = r * 1.732f
        val dy = r * 1.5f
        var row = 0
        var y = -r
        while (y < h + r) {
            var x = if (row % 2 == 0) 0f else dx / 2f
            while (x < w + dx) {
                path.reset()
                for (k in 0 until 6) {
                    val ang = Math.toRadians((60 * k - 30).toDouble())
                    val px = x + (r * Math.cos(ang)).toFloat()
                    val py = y + (r * Math.sin(ang)).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, paint)
                x += dx
            }
            y += dy
            row++
        }
        paint.style = Paint.Style.FILL
    }

    /** Разбор простого SVG-пути (M, L, Q, C, l, Z) в [Path] — так форму дракона удобно хранить строкой. */
    private fun svg(d: String): Path {
        val out = Path()
        val tokens = Regex("[MLQClZz]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var cx = 0f
        var cy = 0f
        fun num(): Float = tokens[i++].toFloat()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t[0].isLetter()) {
                cmd = t[0]
                i++
                if (cmd == 'Z' || cmd == 'z') out.close()
                continue
            }
            when (cmd) {
                'M' -> { cx = num(); cy = num(); out.moveTo(cx, cy); cmd = 'L' }
                'L' -> { cx = num(); cy = num(); out.lineTo(cx, cy) }
                'l' -> { cx += num(); cy += num(); out.lineTo(cx, cy) }
                'Q' -> { val x1 = num(); val y1 = num(); cx = num(); cy = num(); out.quadTo(x1, y1, cx, cy) }
                'C' -> {
                    val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num()
                    cx = num(); cy = num()
                    out.cubicTo(x1, y1, x2, y2, cx, cy)
                }
                else -> i++
            }
        }
        return out
    }

    private fun fill(canvas: Canvas, d: String, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(svg(d), paint)
    }

    private fun stroke(canvas: Canvas, d: String, width: Float, color: Int, dash: Boolean = false) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        if (dash) paint.pathEffect = DashPathEffect(floatArrayOf(0.7f, 2.1f), 0f)
        canvas.drawPath(svg(d), paint)
        paint.pathEffect = null
        paint.strokeCap = Paint.Cap.BUTT
    }

    /** Red Dragon: чёрная фактурная кожа, глянцевое красное кольцо и красный крылатый дракон внутри. */
    private fun dragon(canvas: Canvas, w: Float, h: Float) {
        // Фактура: мелкие светлые и тёмные точки, как тиснёная кожа
        val rnd = Random(5)
        paint.style = Paint.Style.FILL
        val dots = (w * h / 900f).toInt().coerceIn(200, 2500)
        for (i in 0 until dots) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val r = w * (0.002f + rnd.nextFloat() * 0.003f)
            paint.color = if (rnd.nextBoolean()) Color.argb(10 + rnd.nextInt(14), 255, 255, 255) else Color.argb(40 + rnd.nextInt(40), 0, 0, 0)
            canvas.drawCircle(x, y, r, paint)
        }

        val size = minOf(w * 0.96f, h * 0.7f)
        val cx = w / 2f
        val cy = h * 0.42f
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(size / 100f, size / 100f)

        // Красное свечение за эмблемой
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(50f, 50f, 52f, 0x47FF2A1A, 0x00FF2A1A, Shader.TileMode.CLAMP)
        canvas.drawCircle(50f, 50f, 52f, paint)
        paint.shader = null

        val ringShader = LinearGradient(8f, 8f, 92f, 92f, intArrayOf(0xFFFF5A45.toInt(), 0xFFC8100C.toInt(), 0xFF6A0606.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val redShader = LinearGradient(20f, 10f, 80f, 90f, intArrayOf(0xFFFF4B3A.toInt(), 0xFFD4140F.toInt(), 0xFF6E0707.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)

        // Два кольца
        paint.style = Paint.Style.STROKE
        paint.shader = ringShader
        paint.strokeWidth = 3.2f
        canvas.drawCircle(50f, 50f, 46f, paint)
        paint.strokeWidth = 1.1f
        canvas.drawCircle(50f, 50f, 42f, paint)
        paint.shader = null

        paint.shader = redShader
        // Крыло
        paint.style = Paint.Style.FILL
        canvas.drawPath(svg("M51,50 Q40,28 31,15 Q28,32 22,58 Q31,46 33,56 Q37,48 40,61 Q44,51 48,63 Z"), paint)
        paint.shader = null
        stroke(canvas, "M51,50 L28,34 M51,50 L26,46 M51,50 L33,56", 0.6f, 0xFF7A0808.toInt())

        paint.shader = redShader
        // Задняя лапа, тело, хвост
        for ((d, wd) in listOf(
            "M56,70 L66,74 L70,82" to 4.2f,
            "M70,33 C58,29 50,36 52,48 C54,58 62,60 58,70" to 8f,
            "M58,70 C55,80 38,84 31,74 C26,66 36,60 43,66" to 5.6f
        )) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = wd
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(svg(d), paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        canvas.drawPath(svg("M43,66 L40.5,62 L47,63.5 Z"), paint)
        paint.shader = null

        // Чешуйчатые полосы на теле и хвосте
        stroke(canvas, "M70,33 C58,29 50,36 52,48 C54,58 62,60 58,70", 6f, 0xBF6E0707.toInt(), true)
        stroke(canvas, "M58,70 C55,80 38,84 31,74 C26,66 36,60 43,66", 4.4f, 0xBF6E0707.toInt(), true)
        // Блик на шее
        stroke(canvas, "M69,30.5 C58,26.5 48.5,34 50,48 C52,57 60,59 56,69", 1f, 0x8CFF9A85.toInt())

        paint.shader = redShader
        paint.style = Paint.Style.FILL
        rect.set(49.5f, 51.5f, 66.5f, 72.5f)
        canvas.drawOval(rect, paint)
        // Передняя лапа
        stroke(canvas, "M62,60 L72,63 L76,70", 3.6f, 0xFFD4140F.toInt())
        paint.shader = null
        fill(canvas, "M76,70 l2.5,3.5 l-3.5,-1.2 z M76,70 l-0.5,4 l-2,-3 z M70,82 l3,3 l-4,-0.5 z", 0xFFD4140F.toInt())

        // Рога и голова
        paint.shader = redShader
        paint.style = Paint.Style.FILL
        canvas.drawPath(svg("M67,29 Q58,24 54,13 Q63,19 70,27 Z M70,28 Q66,20 65,11 Q71,18 73,27 Z"), paint)
        canvas.drawPath(svg("M64,31 L70,25 L78,26 L86,31 L84,35 L77,34.5 L80,40 L74,37 L69,39 Z"), paint)
        paint.shader = null
        fill(canvas, "M78,34.4 L84,34.2 L77,35.6 Z", 0xFF0A0A0B.toInt())
        fill(canvas, "M79.5,34.2 l0.9,1.7 l0.9,-1.7 z M82.3,34.1 l0.8,1.5 l0.8,-1.6 z", 0xFFF5D9D2.toInt())
        paint.color = 0xFFFFD1C8.toInt()
        canvas.drawCircle(73.2f, 29.5f, 0.9f, paint)
        // Шипы на спине
        fill(canvas, "M55,38 l-3.2,-2 l1,3.4 z M52,46 l-3.5,-0.5 l2,2.6 z M55,56 l-3.2,0.8 l2.5,2 z", 0xFFA30D0B.toInt())

        canvas.restore()
    }

    private fun snow(canvas: Canvas, w: Float, h: Float, count: Int) {
        val rnd = Random(7)
        for (i in 0 until count) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val r = w * (0.004f + rnd.nextFloat() * 0.008f)
            paint.color = Color.argb(120 + rnd.nextInt(120), 255, 255, 255)
            canvas.drawCircle(x, y, r, paint)
        }
    }

    private fun garland(canvas: Canvas, w: Float, h: Float) {
        val colors = intArrayOf(0xFFFF3B47.toInt(), 0xFFFFD60A.toInt(), 0xFF2ECC71.toInt(), 0xFF3D9BFF.toInt())
        val n = 9
        val sag = h * 0.025f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.004f
        paint.color = 0xFF1B1B1B.toInt()
        path.reset()
        path.moveTo(0f, h * 0.012f)
        for (i in 0 until n) {
            val x0 = w * i / n
            val x1 = w * (i + 1) / n
            path.quadTo((x0 + x1) / 2f, h * 0.012f + sag * 2f, x1, h * 0.012f)
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        for (i in 0 until n) {
            val x0 = w * i / n
            val x1 = w * (i + 1) / n
            val cx = (x0 + x1) / 2f
            val cy = h * 0.012f + sag
            val c = colors[i % colors.size]
            paint.color = Color.argb(60, Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawCircle(cx, cy + w * 0.012f, w * 0.03f, paint)
            paint.color = c
            canvas.drawCircle(cx, cy + w * 0.012f, w * 0.014f, paint)
        }
    }

    private fun sun(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.8f
        val cy = h * 0.14f
        paint.style = Paint.Style.FILL
        paint.color = 0x22FFE082
        canvas.drawCircle(cx, cy, w * 0.24f, paint)
        paint.color = 0x33FFE082
        canvas.drawCircle(cx, cy, w * 0.18f, paint)
        paint.color = 0xFFFFD54F.toInt()
        canvas.drawCircle(cx, cy, w * 0.11f, paint)
    }

    private fun cloud(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawCircle(cx + r * 1.1f, cy + r * 0.15f, r * 0.8f, paint)
        canvas.drawCircle(cx - r * 1.0f, cy + r * 0.2f, r * 0.7f, paint)
        rect.set(cx - r * 1.7f, cy + r * 0.2f, cx + r * 1.9f, cy + r * 0.95f)
        canvas.drawRoundRect(rect, r * 0.4f, r * 0.4f, paint)
    }

    private fun waves(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        for (k in 0 until 2) {
            paint.color = if (k == 0) 0x6629B6F6 else 0x8803A9F4.toInt()
            val base = h * (0.9f + 0.04f * k)
            path.reset()
            path.moveTo(0f, h)
            path.lineTo(0f, base)
            var x = 0f
            while (x <= w) {
                path.lineTo(x, base + sin((x / w * 6.28f * 2f) + k * 1.5f) * h * 0.012f)
                x += w / 40f
            }
            path.lineTo(w, h)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    /** Листья и лепестки: повёрнутые овалы разных цветов. */
    private fun leaves(canvas: Canvas, w: Float, h: Float, count: Int, colors: IntArray, size: Float) {
        val rnd = Random(11)
        paint.style = Paint.Style.FILL
        for (i in 0 until count) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val s = w * size * (0.6f + rnd.nextFloat() * 0.8f)
            val c = colors[rnd.nextInt(colors.size)]
            paint.color = Color.argb(150 + rnd.nextInt(90), Color.red(c), Color.green(c), Color.blue(c))
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(rnd.nextFloat() * 360f)
            rect.set(-s, -s * 0.55f, s, s * 0.55f)
            canvas.drawOval(rect, paint)
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
