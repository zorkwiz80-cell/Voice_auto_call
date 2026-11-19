@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Входящий звонок в виде небольшого окна сверху экрана (когда телефон разблокирован).
 * Голосовые команды работают так же, как на полном экране. Если звонок принят —
 * открывается обычный экран разговора.
 */
class InCallPopupActivity : BaseActivity(), Calls.Listener {

    private var started = false
    private var voice: VoiceListener? = null
    private var commandSent = false
    private var avatarKey: String? = "?"

    private lateinit var avatar: AvatarView
    private lateinit var nameTv: TextView
    private lateinit var subTv: TextView
    private lateinit var heardTv: TextView
    private lateinit var eq: EqualizerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.TOP)
        window.setLayout(Ui.MATCH, Ui.WRAP)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        applyBars(Color.TRANSPARENT, Color.TRANSPARENT)
        build()
    }

    private fun build() {
        val a = this
        val wrap = FrameLayout(a)
        wrap.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 6))

        val card = LinearLayout(a)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14))
        onAccent { accent ->
            card.background = Ui.rounded(Theme.mix(accent, Color.BLACK, 0.82f), Ui.dpf(a, 26f))
        }
        card.elevation = Ui.dpf(a, 8f)

        val head = LinearLayout(a)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        avatar = AvatarView(a, Ui.dp(a, 52), 18f)
        head.addView(avatar, Ui.lp(Ui.dp(a, 52), Ui.dp(a, 52)))
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        nameTv = Ui.text(a, "", 20f, Color.WHITE, true)
        nameTv.maxLines = 1
        nameTv.ellipsize = TextUtils.TruncateAt.END
        subTv = Ui.text(a, "Входящий вызов", 14f, 0xB3FFFFFF.toInt())
        subTv.maxLines = 1
        subTv.ellipsize = TextUtils.TruncateAt.END
        col.addView(nameTv, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(subTv, Ui.lp(Ui.MATCH, Ui.WRAP))
        head.addView(col, Ui.margins(Ui.lp(0, Ui.WRAP, 1f), Ui.dp(a, 14), 0, 0, 0))
        eq = EqualizerView(a)
        head.addView(eq, Ui.lp(Ui.dp(a, 64), Ui.dp(a, 24)))
        card.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))

        heardTv = Ui.text(a, "", 13f, 0xB3FFFFFF.toInt())
        heardTv.maxLines = 1
        heardTv.ellipsize = TextUtils.TruncateAt.END
        card.addView(heardTv, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 8), 0, 0))

        val buttons = LinearLayout(a)
        buttons.orientation = LinearLayout.HORIZONTAL
        val decline = pill("Отклонить", Theme.RED) { Calls.reject() }
        val accept = pill("Принять", Theme.GREEN) { Calls.answer(a, false) }
        buttons.addView(decline, Ui.margins(Ui.lp(0, Ui.dp(a, 48), 1f), 0, 0, Ui.dp(a, 8), 0))
        buttons.addView(accept, Ui.margins(Ui.lp(0, Ui.dp(a, 48), 1f), Ui.dp(a, 8), 0, 0, 0))
        card.addView(buttons, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))

        // Нажатие на имя открывает полный экран звонка
        head.setOnClickListener { openFull() }

        wrap.addView(card, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        setContentView(wrap)
    }

    private fun pill(label: String, color: Int, onClick: () -> Unit): TextView {
        val a = this
        val t = Ui.text(a, label, 16f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.background = Ui.ripple(Ui.rounded(color, Ui.dpf(a, 24f)), Ui.dpf(a, 24f))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun openFull() {
        try {
            val i = Intent(this, InCallActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
        }
        finish()
    }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onStart() {
        super.onStart()
        started = true
        Calls.addListener(this)
        render()
    }

    override fun onStop() {
        started = false
        Calls.removeListener(this)
        voice?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        voice?.stop()
        voice = null
        super.onDestroy()
    }

    override fun onCallsChanged() {
        render()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && Calls.ringing() != null) {
            Calls.silence(this)
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ отрисовка

    private fun render() {
        if (!started || isFinishing) return
        val call = Calls.primary()
        if (call == null) {
            finish()
            return
        }
        if (call.state != Call.STATE_RINGING) {
            // Звонок принят (или уже идёт) — показываем обычный экран разговора
            openFull()
            return
        }
        val info = Calls.info(call)
        nameTv.text = Calls.title(info)
        val sub = Calls.subtitle(info)
        subTv.text = if (sub.isEmpty()) "Входящий вызов" else "Входящий · $sub"
        val key = (info?.name ?: "") + "|" + (info?.photo ?: "")
        if (key != avatarKey) {
            avatarKey = key
            avatar.bind(info?.name, info?.photo)
        }
        updateVoice()
    }

    private fun ensureVoice(): VoiceListener {
        val existing = voice
        if (existing != null) return existing
        val v = VoiceListener(
            this, true,
            onCommand = { cmd ->
                commandSent = true
                when (cmd) {
                    Logic.Command.ACCEPT -> {
                        heardTv.text = "Принимаю…"
                        Calls.answer(this, true)
                    }
                    Logic.Command.REJECT -> {
                        heardTv.text = "Отклоняю…"
                        Calls.reject()
                    }
                }
            },
            onHeard = { text -> heardTv.text = "Слышу: «$text»" },
            onLevel = { lvl -> eq.level = lvl },
            onStatus = { st -> if (st.isNotEmpty()) heardTv.text = st }
        )
        voice = v
        return v
    }

    private fun updateVoice() {
        val enabled = Prefs.voiceEnabled(this)
        if (!enabled) {
            heardTv.text = ""
            voice?.stop()
            eq.level = 0f
            return
        }
        val a = Prefs.acceptWords(this).firstOrNull() ?: "принять"
        val r = Prefs.rejectWords(this).firstOrNull() ?: "отклонить"
        val want = !Calls.announcing && !commandSent
        if (want) {
            if (voice?.running != true && heardTv.text.isNullOrEmpty()) heardTv.text = "Скажите «$a» или «$r»"
            ensureVoice().start()
        } else {
            if (Calls.announcing) heardTv.text = "Сейчас скажу, кто звонит…"
            val v = voice
            if (v != null && v.running) v.stop()
            eq.level = 0f
        }
    }
}
