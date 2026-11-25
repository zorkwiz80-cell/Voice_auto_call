package com.example.voiceanswer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Маленькие помощники для рисования интерфейса из кода. */
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

    fun rounded(color: Int, radiusPx: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusPx
        return g
    }

    fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    /** Заливка акцентным цветом; для радужной темы — градиент из всех цветов. */
    fun accentFill(radiusPx: Float, isOval: Boolean = false): GradientDrawable {
        val g = GradientDrawable()
        if (Theme.rainbow) {
            g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT)
            g.setColors(Theme.rainbowColors)
        } else {
            g.setColor(Theme.accent)
        }
        if (isOval) g.shape = GradientDrawable.OVAL else g.cornerRadius = radiusPx
        return g
    }

    /** Нажимаемая поверхность с «рябью» по форме содержимого. */
    fun ripple(content: Drawable, radiusPx: Float, isOval: Boolean = false): Drawable {
        val mask = GradientDrawable()
        mask.setColor(Color.BLACK)
        if (isOval) mask.shape = GradientDrawable.OVAL else mask.cornerRadius = radiusPx
        val rippleColor = if (Theme.light) 0x33000000 else 0x33FFFFFF
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun text(c: Context, s: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    fun light(c: Context, s: String, sp: Float, color: Int): TextView {
        val t = text(c, s, sp, color)
        t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        return t
    }

    fun icon(c: Context, res: Int, color: Int): ImageView {
        val i = ImageView(c)
        i.setImageResource(res)
        i.setColorFilter(color)
        return i
    }

    fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)

    fun lp(w: Int, h: Int, weight: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    fun margins(p: LinearLayout.LayoutParams, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        p.setMargins(l, t, r, b)
        return p
    }

    /** Круглая кнопка с иконкой по центру. */
    fun roundButton(c: Context, sizeDp: Int, fill: Drawable, iconRes: Int, iconColor: Int, iconDp: Int = 28): FrameLayout {
        val f = FrameLayout(c)
        f.background = ripple(fill, 0f, true)
        val i = icon(c, iconRes, iconColor)
        f.addView(i, FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER))
        f.isClickable = true
        f.layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        return f
    }

    /** Нижняя шторка с пунктами. */
    fun sheet(
        a: Activity, title: String, subtitle: String?,
        actions: List<Triple<Int, String, () -> Unit>>,
        extra: ((LinearLayout, () -> Unit) -> Unit)? = null
    ) {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(a, 20), dp(a, 20), dp(a, 20), dp(a, 24))
        val r = dpf(a, 26f)
        val g = GradientDrawable()
        g.setColor(Theme.card)
        g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        box.background = g

        box.addView(text(a, title, 22f, Theme.text, true))
        if (!subtitle.isNullOrBlank()) {
            box.addView(text(a, subtitle, 15f, Theme.sub), margins(lp(WRAP, WRAP), 0, dp(a, 2), 0, 0))
        }
        // Дополнительные сведения (номера, время, последние звонки) — между заголовком и действиями
        extra?.invoke(box) { d.dismiss() }
        for ((res, label, action) in actions) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(a, 6), dp(a, 14), dp(a, 6), dp(a, 14))
            row.background = ripple(ColorDrawable(Color.TRANSPARENT), dpf(a, 12f))
            row.addView(icon(a, res, Theme.accent), lp(dp(a, 24), dp(a, 24)))
            row.addView(text(a, label, 17f, Theme.text), margins(lp(WRAP, WRAP), dp(a, 16), 0, 0, 0))
            row.setOnClickListener {
                d.dismiss()
                action()
            }
            box.addView(row, margins(lp(MATCH, WRAP), 0, dp(a, 6), 0, 0))
        }
        val scroll = android.widget.ScrollView(a)
        scroll.addView(box, lp(MATCH, WRAP))
        d.setContentView(scroll)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(MATCH, WRAP)
            w.setGravity(Gravity.BOTTOM)
        }
        d.show()
    }

    fun setVisible(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
