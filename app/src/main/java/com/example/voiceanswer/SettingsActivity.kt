@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Настройки: голос, слова-команды, цвета, инструкция. */
class SettingsActivity : BaseActivity() {

    private lateinit var scroll: ScrollView
    private var test: VoiceListener? = null
    private var testResult: TextView? = null
    private var testButton: TextView? = null
    private var built = false

    private companion object {
        const val REQ_IMAGE = 77
    }

    override fun recreateOnThemeChange(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() {
        super.onResume()
        if (built && !isFinishing) rebuild()
    }

    override fun onPause() {
        stopTest()
        super.onPause()
    }

    private fun rebuild() {
        val y = scroll.scrollY
        stopTest()
        clearAccentUpdaters()
        Theme.load(this)
        markThemeBuilt()
        applyWindowTheme()
        build()
        scroll.post { scroll.scrollY = y }
    }

    // ------------------------------------------------------------------ помощники

    private fun card(parent: LinearLayout, title: String, hint: String? = null): LinearLayout {
        val a = this
        val c = LinearLayout(a)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16))
        c.background = Ui.rounded(Theme.card, Ui.dpf(a, 24f))
        val t = Ui.text(a, title, 18f, Theme.text, true)
        c.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP))
        if (hint != null) {
            c.addView(Ui.text(a, hint, 14f, Theme.sub), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 4), 0, 0))
        }
        parent.addView(c, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), 0))
        return c
    }

    private fun pill(label: String, onClick: () -> Unit): TextView {
        val a = this
        val b = Ui.text(a, label, 15f, Color.WHITE, true)
        b.gravity = Gravity.CENTER
        b.setPadding(Ui.dp(a, 18), Ui.dp(a, 11), Ui.dp(a, 18), Ui.dp(a, 11))
        b.background = Ui.ripple(Ui.accentFill(Ui.dpf(a, 22f)), Ui.dpf(a, 22f))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun softPill(label: String, onClick: () -> Unit): TextView {
        val a = this
        val b = Ui.text(a, label, 15f, Theme.text)
        b.gravity = Gravity.CENTER
        b.setPadding(Ui.dp(a, 18), Ui.dp(a, 11), Ui.dp(a, 18), Ui.dp(a, 11))
        b.background = Ui.ripple(Ui.rounded(Theme.card2, Ui.dpf(a, 22f)), Ui.dpf(a, 22f))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun switchRow(parent: LinearLayout, title: String, sub: String?, key: String) {
        val a = this
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(a, title, 16f, Theme.text), Ui.lp(Ui.MATCH, Ui.WRAP))
        if (sub != null) col.addView(Ui.text(a, sub, 13f, Theme.sub), Ui.lp(Ui.MATCH, Ui.WRAP))
        row.addView(col, Ui.margins(Ui.lp(0, Ui.WRAP, 1f), 0, 0, Ui.dp(a, 12), 0))
        val sw = Switch(a)
        sw.isChecked = Prefs.get(a, key)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFB0B0B8.toInt()))
        onAccent { c ->
            sw.trackTintList = ColorStateList(states, intArrayOf(c, Theme.card2))
        }
        sw.setOnCheckedChangeListener { _, v ->
            Prefs.set(a, key, v)
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        parent.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 14), 0, 0))
    }

    private fun label(parent: LinearLayout, s: String) {
        parent.addView(Ui.text(this, s, 14f, Theme.sub, true), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 16), 0, Ui.dp(this, 6)))
    }

    // ------------------------------------------------------------------ экран

    private fun build() {
        val a = this
        scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, 0, Ui.dp(a, 32))
        scroll.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // Шапка
        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.roundButton(a, 46, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_arrow_back, Theme.text, 24)
        back.setOnClickListener { finish() }
        top.addView(back)
        top.addView(Ui.light(a, "Настройки", 30f, Theme.text), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 6), 0, 0, 0))
        col.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 10), Ui.dp(a, 8), 0, Ui.dp(a, 4)))

        buildStatus(col)
        buildVoice(col)
        buildRinging(col)
        buildWords(col)
        buildRecording(col)
        buildTest(col)
        buildLook(col)
        buildHelp(col)

        val about = Ui.text(a, "Голосовой телефон 3.30", 13f, Theme.sub)
        about.gravity = Gravity.CENTER
        col.addView(about, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 20), 0, 0))

        setContentView(scroll)
        built = true
    }

    private fun buildStatus(col: LinearLayout) {
        val a = this
        val c = card(col, "Состояние")
        val isDef = Perms.isDefaultDialer(a)
        val missing = Perms.missing(a)
        c.addView(
            Ui.text(a, if (isDef) "✓ Основной телефон: да" else "✗ Основной телефон: нет", 16f, if (isDef) Theme.GREEN else Theme.RED),
            Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0)
        )
        c.addView(
            Ui.text(a, if (missing.isEmpty()) "✓ Разрешения: все выданы" else "✗ Не хватает разрешений: ${missing.size}", 16f,
                if (missing.isEmpty()) Theme.GREEN else Theme.RED),
            Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 4), 0, 0)
        )
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        if (!isDef) row.addView(pill("Сделать основным") { Perms.requestDefaultDialer(a, 1) })
        if (missing.isNotEmpty()) {
            row.addView(softPill("Разрешения") { requestPermissions(missing.toTypedArray(), 2) }, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 8), 0, 0, 0))
        }
        if (row.childCount > 0) c.addView(row, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))
    }

    private fun buildVoice(col: LinearLayout) {
        val a = this
        val c = card(col, "Голос и звук")
        switchRow(c, "Голосовое управление", "Слушать команды, пока идёт входящий звонок", Prefs.K_VOICE)
        switchRow(c, "Озвучивать, кто звонит", "Телефон скажет «Звонит Мама» перед тем, как слушать вас", Prefs.K_ANNOUNCE)
        switchRow(c, "Громкая связь после ответа голосом", "Когда вы сказали «принять»", Prefs.K_SPEAKER_VOICE)
        switchRow(c, "Громкая связь после ответа кнопкой", "Когда ответили пальцем на экране", Prefs.K_SPEAKER_BUTTON)
        switchRow(c, "Чёткий голос", "Включать микрофон и шумоподавление в начале разговора (если телефон это поддерживает)", Prefs.K_CLEAR_VOICE)
        switchRow(c, "Если подключены наушники", "Говорить через наушники, динамик не включать", Prefs.K_HEADSET_FIRST)
        switchRow(c, "Слушать команды через наушники", "Bluetooth-наушники: микрофон наушников (по возможности)", Prefs.K_HEADSET_MIC)
        switchRow(c, "Тихий рингтон", "Когда звонят, громкость звонка 50%; после звонка возвращается прежняя", Prefs.K_QUIET_RINGER)
    }

    /** Ряд кнопок-вариантов: выбранный подсвечен цветом темы. */
    private fun choices(parent: LinearLayout, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        val a = this
        val flow = FlowLayout(a)
        flow.hGap = Ui.dp(a, 8)
        flow.vGap = Ui.dp(a, 8)
        for ((id, name) in options) {
            val b = if (id == current) pill(name) {} else softPill(name) { onPick(id) }
            flow.addView(b)
        }
        parent.addView(flow, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun buildRinging(col: LinearLayout) {
        val a = this
        val c = card(col, "Входящий звонок")
        label(c, "КАК ПОКАЗЫВАТЬ ВХОДЯЩИЙ")
        choices(c, listOf("full" to "На весь экран", "popup" to "Окно сверху"), Prefs.callMode(a)) {
            Prefs.setCallMode(a, it)
            rebuild()
        }
        c.addView(
            Ui.text(a, "«Окно сверху» — небольшая панель с кнопками «Принять» и «Отклонить», пока телефон разблокирован. Голосовые команды работают и в ней. Если телефон заблокирован, звонок всё равно открывается на весь экран.", 13f, Theme.sub),
            Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 8), 0, 0)
        )
        switchRow(c, "Вибрация при разговоре", "Короткий сигнал, когда разговор начался и когда закончился", Prefs.K_VIBRATE_CALL)
        label(c, "РИНГТОН")
        switchRow(c, "Нарастающий рингтон", "Мелодия звонка начинается тихо и постепенно становится громче", Prefs.K_RAMP)
        label(c, "ВРЕМЯ НАРАСТАНИЯ ДО ПОЛНОЙ ГРОМКОСТИ")
        val cur = Prefs.rampSeconds(a).toString()
        choices(c, listOf("5" to "5 сек", "10" to "10 сек", "12" to "12 сек", "20" to "20 сек", "30" to "30 сек"), cur) {
            Prefs.setRampSeconds(a, it.toInt())
            rebuild()
        }
        c.addView(
            Ui.text(a, "Используется мелодия звонка, выбранная в настройках телефона. Кнопка громкости заглушает звонок.", 13f, Theme.sub),
            Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 8), 0, 0)
        )
        c.addView(pill("Проверить рингтон") {
            val am = getSystemService(android.media.AudioManager::class.java)
            if (!Ringer.playing && am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                Toast.makeText(a, "Включите звук телефона: сейчас беззвучный режим или только вибрация", Toast.LENGTH_LONG).show()
            } else {
                Ringer.test(a)
                Toast.makeText(a, if (Ringer.playing) "Звонит с нарастанием. Нажмите ещё раз, чтобы остановить" else "Остановлено", Toast.LENGTH_SHORT).show()
            }
        }, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))
    }

    private fun buildWords(col: LinearLayout) {
        val a = this
        val c = card(col, "Слова-команды", "Добавьте любое слово — например «персик» — и телефон будет реагировать на него.")
        label(c, "ПРИНЯТЬ ЗВОНОК")
        wordsBlock(c, true)
        label(c, "ОТКЛОНИТЬ ЗВОНОК")
        wordsBlock(c, false)
        c.addView(
            Ui.text(a, "Если телефон слышит вместо «принять» другое слово (например «привет»), просто добавьте его в список «Принять».", 13f, Theme.sub),
            Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 14), 0, 0)
        )
        c.addView(softPill("Вернуть стандартные слова") {
            Prefs.resetWords(a)
            rebuild()
        }, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 16), 0, 0))
    }

    private fun wordsBlock(parent: LinearLayout, accept: Boolean) {
        val a = this
        val words = if (accept) Prefs.acceptWords(a) else Prefs.rejectWords(a)
        val flow = FlowLayout(a)
        flow.hGap = Ui.dp(a, 8)
        flow.vGap = Ui.dp(a, 8)
        for (w in words) {
            val chip = LinearLayout(a)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.gravity = Gravity.CENTER_VERTICAL
            chip.setPadding(Ui.dp(a, 14), Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4))
            chip.background = Ui.rounded(Theme.card2, Ui.dpf(a, 20f))
            chip.addView(Ui.text(a, w, 15f, Theme.text))
            val x = Ui.roundButton(a, 30, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_close, Theme.sub, 16)
            x.setOnClickListener {
                Prefs.removeWord(a, accept, w)
                rebuild()
            }
            chip.addView(x, Ui.margins(Ui.lp(Ui.dp(a, 30), Ui.dp(a, 30)), Ui.dp(a, 2), 0, 0, 0))
            flow.addView(chip)
        }
        if (words.isEmpty()) {
            flow.addView(Ui.text(a, "Слов нет — добавьте хотя бы одно", 14f, Theme.sub))
        }
        parent.addView(flow, Ui.lp(Ui.MATCH, Ui.WRAP))

        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val input = EditText(a)
        input.hint = if (accept) "Новое слово, например: персик" else "Новое слово, например: груша"
        input.setHintTextColor(Theme.sub)
        input.setTextColor(Theme.text)
        input.textSize = 15f
        input.isSingleLine = true
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.background = Ui.rounded(Theme.card2, Ui.dpf(a, 18f))
        input.setPadding(Ui.dp(a, 16), Ui.dp(a, 11), Ui.dp(a, 16), Ui.dp(a, 11))
        val add = {
            val ok = Prefs.addWord(a, accept, input.text.toString())
            if (ok) {
                rebuild()
            } else {
                Toast.makeText(a, "Введите новое слово (такого ещё нет)", Toast.LENGTH_SHORT).show()
            }
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                add()
                true
            } else false
        }
        row.addView(input, Ui.lp(0, Ui.WRAP, 1f))
        row.addView(pill("Добавить") { add() }, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 8), 0, 0, 0))
        parent.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
    }

    private fun buildRecording(col: LinearLayout) {
        val a = this
        val c = card(
            col, "Запись разговоров",
            "В этом приложении записи разговоров нет. Запись ведёт стандартное приложение «Телефон» вашего производителя (Google, Samsung, Xiaomi и др.) — включите там автоматическую запись звонков."
        )
        c.addView(
            Ui.text(a, "Android не позволяет другому приложению включать системную запись. Стандартное приложение работает со звонками, только пока оно выбрано основным для звонков: выберите его в списке приложений по умолчанию (в разделе «Приложение для звонков»).", 13f, Theme.sub),
            Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 4), 0, 0)
        )
        c.addView(pill("Открыть приложения по умолчанию") {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(a, "Не удалось открыть настройки", Toast.LENGTH_LONG).show()
                }
            }
        }, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 14), 0, 0))
    }

    private fun buildTest(col: LinearLayout) {
        val a = this
        val c = card(col, "Проверка голоса", "Нажмите и скажите слово — приложение покажет, что услышало и что сделает при звонке.")
        val result = Ui.text(a, "", 16f, Theme.text)
        testResult = result
        val btn = pill("Проверить") { toggleTest() }
        testButton = btn
        c.addView(btn, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))
        c.addView(result, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))
    }

    private fun toggleTest() {
        if (test?.running == true) {
            stopTest()
            return
        }
        if (!Perms.has(this, Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            return
        }
        val l = VoiceListener(
            this, false,
            onCommand = { cmd ->
                testResult?.text = if (cmd == Logic.Command.ACCEPT) "✓ Это команда «ПРИНЯТЬ»" else "✓ Это команда «ОТКЛОНИТЬ»"
            },
            onHeard = { t -> testResult?.text = "Слышу: «$t»" },
            onLevel = { },
            onStatus = { s -> if (s.isNotEmpty()) testResult?.text = s }
        )
        test = l
        testResult?.text = "Слушаю… говорите"
        testButton?.text = "Остановить"
        l.start()
    }

    private fun stopTest() {
        test?.stop()
        test = null
        testButton?.text = "Проверить"
    }

    private fun buildLook(col: LinearLayout) {
        val a = this
        val c = card(col, "Цвета телефона")
        label(c, "АКЦЕНТ")
        val flow = FlowLayout(a)
        flow.hGap = Ui.dp(a, 12)
        flow.vGap = Ui.dp(a, 12)
        for (ac in Theme.accents) {
            val sw = FrameLayout(a)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            when (ac.id) {
                "rainbow" -> {
                    g.setGradientType(GradientDrawable.SWEEP_GRADIENT)
                    g.setColors(Theme.rainbowColors + Theme.rainbowColors[0])
                }
                "custom" -> {
                    g.setGradientType(GradientDrawable.SWEEP_GRADIENT)
                    g.setColors(intArrayOf(Color.WHITE, 0xFF9B5CFF.toInt(), Color.WHITE))
                }
                else -> g.setColor(ac.color)
            }
            val selected = Theme.accentId == ac.id
            if (selected) g.setStroke(Ui.dp(a, 3), Theme.text)
            sw.background = g
            if (ac.id == "custom") {
                val t = Ui.text(a, "+", 22f, Color.BLACK, true)
                t.gravity = Gravity.CENTER
                sw.addView(t, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            } else if (selected) {
                sw.addView(Ui.icon(a, R.drawable.ic_check, Color.WHITE), FrameLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22), Gravity.CENTER))
            }
            sw.isClickable = true
            sw.setOnClickListener {
                Prefs.setAccent(a, ac.id)
                rebuild()
            }
            flow.addView(sw, Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)))
        }
        c.addView(flow, Ui.lp(Ui.MATCH, Ui.WRAP))
        val names = Theme.accents.firstOrNull { it.id == Theme.accentId }?.name ?: ""
        c.addView(Ui.text(a, names, 14f, Theme.sub), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))

        if (Theme.accentId == "custom") {
            val bar = SeekBar(a)
            bar.max = 360
            bar.progress = Theme.hue
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, Theme.rainbowColors + Theme.rainbowColors[0])
            g.cornerRadius = Ui.dpf(a, 6f)
            g.setSize(Ui.MATCH, Ui.dp(a, 12))
            bar.progressDrawable = g
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Prefs.setHue(a, s?.progress ?: 0)
                    rebuild()
                }
            })
            c.addView(bar, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))
        }

        label(c, "ЭКРАН ЗВОНКА")
        val callRow = LinearLayout(a)
        callRow.orientation = LinearLayout.HORIZONTAL
        for ((id, name) in listOf("dark" to "Тёмный", "black" to "Чёрный", "bright" to "Яркий")) {
            val sel = Prefs.callBg(a) == id
            val t = Ui.text(a, name, 15f, if (sel) Color.WHITE else Theme.text, sel)
            t.gravity = Gravity.CENTER
            t.setPadding(Ui.dp(a, 8), Ui.dp(a, 11), Ui.dp(a, 8), Ui.dp(a, 11))
            val fill = if (sel) Ui.accentFill(Ui.dpf(a, 20f)) else Ui.rounded(Theme.card2, Ui.dpf(a, 20f))
            t.background = Ui.ripple(fill, Ui.dpf(a, 20f))
            t.isClickable = true
            t.setOnClickListener {
                Prefs.setCallBg(a, id)
                rebuild()
            }
            callRow.addView(t, Ui.margins(Ui.lp(0, Ui.WRAP, 1f), 0, 0, Ui.dp(a, 8), 0))
        }
        c.addView(callRow, Ui.lp(Ui.MATCH, Ui.WRAP))

        label(c, "ФОН ПРИЛОЖЕНИЯ")
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        for ((id, name) in listOf("black" to "Чёрный", "dark" to "Тёмный", "light" to "Светлый")) {
            val sel = Theme.bgMode == id
            val t = Ui.text(a, name, 15f, if (sel) Color.WHITE else Theme.text, sel)
            t.gravity = Gravity.CENTER
            t.setPadding(Ui.dp(a, 8), Ui.dp(a, 11), Ui.dp(a, 8), Ui.dp(a, 11))
            val fill = if (sel) Ui.accentFill(Ui.dpf(a, 20f)) else Ui.rounded(Theme.card2, Ui.dpf(a, 20f))
            t.background = Ui.ripple(fill, Ui.dpf(a, 20f))
            t.isClickable = true
            t.setOnClickListener {
                Prefs.setBg(a, id)
                rebuild()
            }
            row.addView(t, Ui.margins(Ui.lp(0, Ui.WRAP, 1f), 0, 0, Ui.dp(a, 8), 0))
        }
        c.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))

        label(c, "ТЕМА ФОНА")
        choices(c, Backgrounds.options.map { it.id to it.name }, Prefs.bgTheme(a)) { id ->
            if (id == "custom") {
                pickImage()
            } else {
                Prefs.setBgTheme(a, id)
                rebuild()
            }
        }
        if (Prefs.bgTheme(a) == "custom") {
            c.addView(softPill(if (Backgrounds.hasCustom(a)) "Выбрать другую картинку" else "Выбрать картинку") { pickImage() },
                Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
        }

        label(c, "ПРОЗРАЧНОСТЬ ПАНЕЛЕЙ: " + Prefs.transparency(a) + "%")
        val bar = SeekBar(a)
        bar.max = 70
        bar.progress = Prefs.transparency(a)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                Prefs.setTransparency(a, s?.progress ?: 0)
                rebuild()
            }
        })
        c.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        c.addView(
            Ui.text(a, if (Theme.themed) "Карточки и панели становятся прозрачными, и сквозь них виден фон." else "Прозрачность заметна, когда выбрана тема фона.", 13f, Theme.sub),
            Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 4), 0, 0)
        )
    }

    private fun pickImage() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            startActivityForResult(Intent.createChooser(i, "Выберите картинку для фона"), REQ_IMAGE)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть выбор картинки", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Toast.makeText(this, "Загружаю картинку…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = Backgrounds.saveCustom(this, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    Prefs.setBgTheme(this, "custom")
                    rebuild()
                } else {
                    Toast.makeText(this, "Не удалось прочитать картинку", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun buildHelp(col: LinearLayout) {
        val a = this
        val c = card(col, "Инструкция: чтобы всё работало и телефон не ругался")

        fun step(n: Int, title: String, body: String, btn: String?, action: (() -> Unit)?) {
            c.addView(Ui.text(a, "$n. $title", 16f, Theme.text, true), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 16), 0, 0))
            c.addView(Ui.text(a, body, 14f, Theme.sub), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 3), 0, 0))
            if (btn != null && action != null) {
                c.addView(softPill(btn) { action() }, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 8), 0, 0))
            }
        }

        step(1, "Сделайте приложение основным телефоном",
            "Без этого оно не сможет показывать экран звонка и отвечать. В окне выберите «Голосовой ответ» → «Установить по умолчанию».",
            "Приложения по умолчанию") { Perms.open(a, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }

        step(2, "Выдайте разрешения",
            "Микрофон, телефон, журнал звонков, контакты, уведомления, «устройства поблизости» (для Bluetooth-наушников). Везде «Разрешить».",
            "Открыть разрешения приложения") { Perms.appDetails(a) }

        step(3, "Разрешите работу без ограничений",
            "Батарея → «Без ограничений». На TECNO/Infinix/Itel: Диспетчер телефона → Автозапуск → включить для «Голосовой ответ». Тогда система не будет убивать приложение.",
            "Настройки батареи") { Perms.open(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

        if (Build.VERSION.SDK_INT >= 34) {
            step(4, "Экран звонка поверх блокировки",
                "Разрешите «Полноэкранные уведомления», чтобы экран звонка включался, когда телефон заблокирован.",
                "Открыть") {
                Perms.open(a, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + packageName)))
            }
        }

        step(5, "Если при установке Play Защита ругается",
            "Приложение не из Google Play, поэтому система может предупредить. Нажмите «Подробнее» → «Всё равно установить». Чтобы предупреждений не было совсем: Google Play → ваш профиль → Play Защита → ⚙ → выключить «Проверять приложения с помощью Play Защиты».",
            null, null)

        step(6, "Распознавание речи",
            "Нужно приложение «Google» (оно распознаёт слова). Для быстрой работы без интернета скачайте русский язык: Настройки → Google → Все службы → Голосовой ввод → Распознавание речи офлайн → Русский.",
            null, null)

        step(7, "Наушники",
            "Если наушники подключены, звук идёт в них, а динамик не включается (можно отключить в «Голос и звук»). Проводные наушники слушают голос сами; у Bluetooth это зависит от модели.",
            null, null)

        step(8, "Если что-то не работает",
            "Нажмите «Проверить» выше и скажите слово — так видно, слышит ли приложение. Помните: пока звонит громкая мелодия, голос слышно хуже — включите «Тихий рингтон». Вернуть обычный телефон можно в Настройки → Приложения по умолчанию → Приложение для звонков.",
            null, null)
    }
}
