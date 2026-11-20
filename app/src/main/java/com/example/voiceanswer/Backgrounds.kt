@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
            "dragon" -> { top = 0xFF080203.toInt(); bottom = 0xFF6E0A0C.toInt() }
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

    /** Red Dragon: чёрно-красный фон, драконья чешуя, жар снизу и искры. */
    private fun dragon(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(w * 0.5f, h * 1.05f, h * 0.7f, 0xAAFF5A1F.toInt(), 0x00FF5A1F, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.004f
        paint.color = 0x40FF2A2A
        val r = w * 0.09f
        var row = 0
        var y = 0f
        while (y < h + r) {
            var x = if (row % 2 == 0) 0f else r
            while (x < w + 2 * r) {
                rect.set(x - r, y - r, x + r, y + r)
                canvas.drawArc(rect, 0f, 180f, false, paint)
                x += 2 * r
            }
            y += r * 0.9f
            row++
        }
        paint.style = Paint.Style.FILL
        val rnd = Random(5)
        for (i in 0 until 45) {
            val x = rnd.nextFloat() * w
            val y2 = h * (0.3f + rnd.nextFloat() * 0.7f)
            val s2 = w * (0.003f + rnd.nextFloat() * 0.006f)
            paint.color = Color.argb(80 + rnd.nextInt(150), 255, 120 + rnd.nextInt(100), 30)
            canvas.drawCircle(x, y2, s2, paint)
        }
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
