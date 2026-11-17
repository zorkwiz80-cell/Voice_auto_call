@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Экран звонка: входящий (с голосовым управлением), исходящий и разговор. */
class InCallActivity : BaseActivity(), Calls.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private var started = false
    private var voice: VoiceListener? = null
    private var voiceOffForThisCall = false
    private var proximity: PowerManager.WakeLock? = null
    private var pulseAnim: ObjectAnimator? = null
    private var finishScheduled = false
    private var dtmfText = ""
    private var silenced = false
    private var commandSent = false
    private var avatarKey: String? = "?"
    private var sc = 1f

    private lateinit var root: FrameLayout
    private lateinit var chip: TextView
    private lateinit var pulse: PulseView
    private lateinit var avatarFrame: FrameLayout
    private lateinit var avatar: AvatarView
    private lateinit var nameTv: TextView
    private lateinit var numberTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var routeTv: TextView
    private lateinit var voiceCard: LinearLayout
    private lateinit var voiceTitle: TextView
    private lateinit var heardTv: TextView
    private lateinit var eq: EqualizerView
    private lateinit var micToggle: FrameLayout
    private lateinit var quietBtn: TextView
    private lateinit var ringButtons: LinearLayout
    private lateinit var acceptBtn: View
    private lateinit var endBox: LinearLayout
    private lateinit var activeBox: LinearLayout
    private lateinit var row1: LinearLayout
    private lateinit var row2: LinearLayout
    private lateinit var row3: LinearLayout
    private lateinit var keypadBox: LinearLayout
    private lateinit var dtmfTv: TextView
    private lateinit var bMute: ControlButton
    private lateinit var bKeypad: ControlButton
    private lateinit var bSpeaker: ControlButton
    private lateinit var bHold: ControlButton
    private lateinit var bRecord: ControlButton
    private lateinit var bAdd: ControlButton
    private lateinit var bHeadset: ControlButton
    private lateinit var bMerge: ControlButton
    private lateinit var bSwap: ControlButton

    private val timer = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hdp = resources.configuration.screenHeightDp
        sc = when {
            hdp < 620 -> 0.66f
            hdp < 700 -> 0.78f
            hdp < 780 -> 0.9f
            else -> 1f
        }
        buildUi()
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "voiceanswer:proximity")
                proximity?.setReferenceCounted(false)
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ интерфейс

    private fun s(v: Int): Int = Ui.dp(this, (v * sc).toInt())

    private fun buildUi() {
        val a = this
        root = FrameLayout(a)
        onAccent { accent ->
            val mode = Prefs.callBg(a)
            val top = when (mode) {
                "black" -> 0xFF000000.toInt()
                "bright" -> Theme.mix(accent, Color.BLACK, 0.55f)
                else -> Theme.mix(accent, Color.BLACK, 0.84f)
            }
            val bottom = if (mode == "black") 0xFF000000.toInt() else 0xFF050508.toInt()
            root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            window.statusBarColor = top
            window.navigationBarColor = bottom
        }

        val outer = LinearLayout(a)
        outer.orientation = LinearLayout.VERTICAL
        root.addView(outer, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        // Прокручиваемая середина
        val scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = true
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8))
        scroll.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        outer.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        chip = Ui.text(a, "Входящий вызов", 14f, Color.WHITE)
        chip.setPadding(Ui.dp(a, 16), Ui.dp(a, 7), Ui.dp(a, 16), Ui.dp(a, 7))
        chip.background = Ui.rounded(0x26FFFFFF, Ui.dpf(a, 20f))
        col.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP))

        // Аватарка (фото контакта) с расходящимися кругами
        avatarFrame = FrameLayout(a)
        pulse = PulseView(a)
        avatarFrame.addView(pulse, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        avatar = AvatarView(a, s(116), 44f * sc)
        avatarFrame.addView(avatar, FrameLayout.LayoutParams(s(116), s(116), Gravity.CENTER))
        col.addView(avatarFrame, Ui.margins(Ui.lp(s(230), s(230)), 0, Ui.dp(a, 6), 0, 0))

        nameTv = Ui.text(a, "", 32f * sc, Color.WHITE, true)
        nameTv.gravity = Gravity.CENTER
        nameTv.maxLines = 2
        nameTv.ellipsize = TextUtils.TruncateAt.END
        col.addView(nameTv, Ui.lp(Ui.MATCH, Ui.WRAP))

        numberTv = Ui.text(a, "", 17f, 0xB3FFFFFF.toInt())
        numberTv.gravity = Gravity.CENTER
        col.addView(numberTv, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 4), 0, 0))

        statusTv = Ui.text(a, "", 19f, Color.WHITE)
        statusTv.gravity = Gravity.CENTER
        col.addView(statusTv, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 8), 0, 0))

        routeTv = Ui.text(a, "", 14f, 0x99FFFFFF.toInt())
        routeTv.gravity = Gravity.CENTER
        col.addView(routeTv, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 2), 0, 0))

        // Карточка голосового управления
        voiceCard = LinearLayout(a)
        voiceCard.orientation = LinearLayout.HORIZONTAL
        voiceCard.gravity = Gravity.CENTER_VERTICAL
        voiceCard.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10))
        voiceCard.background = Ui.rounded(0x1FFFFFFF, Ui.dpf(a, 22f))
        val textCol = LinearLayout(a)
        textCol.orientation = LinearLayout.VERTICAL
        voiceTitle = Ui.text(a, "", 15f, Color.WHITE, true)
        heardTv = Ui.text(a, "", 13f, 0xB3FFFFFF.toInt())
        heardTv.maxLines = 1
        heardTv.ellipsize = TextUtils.TruncateAt.END
        eq = EqualizerView(a)
        textCol.addView(voiceTitle, Ui.lp(Ui.MATCH, Ui.WRAP))
        textCol.addView(eq, Ui.margins(Ui.lp(Ui.dp(a, 110), Ui.dp(a, 24)), 0, Ui.dp(a, 4), 0, Ui.dp(a, 2)))
        textCol.addView(heardTv, Ui.lp(Ui.MATCH, Ui.WRAP))
        voiceCard.addView(textCol, Ui.lp(0, Ui.WRAP, 1f))
        micToggle = Ui.roundButton(a, 46, Ui.oval(0x33FFFFFF), R.drawable.ic_mic, Color.WHITE, 22)
        micToggle.setOnClickListener {
            voiceOffForThisCall = !voiceOffForThisCall
            render()
        }
        voiceCard.addView(micToggle)
        col.addView(voiceCard, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 14), 0, 0))

        quietBtn = Ui.text(a, "Заглушить звонок", 14f, 0xCCFFFFFF.toInt())
        quietBtn.setPadding(Ui.dp(a, 16), Ui.dp(a, 9), Ui.dp(a, 16), Ui.dp(a, 9))
        quietBtn.background = Ui.ripple(Ui.rounded(0x00000000, Ui.dpf(a, 20f)), Ui.dpf(a, 20f))
        quietBtn.isClickable = true
        quietBtn.setOnClickListener {
            Calls.silence(a)
            silenced = true
            quietBtn.visibility = View.GONE
        }
        col.addView(quietBtn, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 4), 0, 0))

        // Клавиатура (тональный набор) — скрыта, пока не нажата кнопка
        keypadBox = LinearLayout(a)
        keypadBox.orientation = LinearLayout.VERTICAL
        keypadBox.gravity = Gravity.CENTER_HORIZONTAL
        dtmfTv = Ui.light(a, "", 28f, Color.WHITE)
        dtmfTv.gravity = Gravity.CENTER
        dtmfTv.maxLines = 1
        dtmfTv.ellipsize = TextUtils.TruncateAt.START
        keypadBox.addView(dtmfTv, Ui.lp(Ui.MATCH, Ui.WRAP))
        keypadBox.addView(
            KeypadView(a, if (sc < 0.8f) 50 else 58, Color.WHITE, 0x26FFFFFF,
                onDown = { ch ->
                    dtmfText += ch
                    dtmfTv.text = dtmfText
                    try {
                        Calls.primary()?.playDtmfTone(ch)
                    } catch (_: Exception) {
                    }
                },
                onUp = {
                    try {
                        Calls.primary()?.stopDtmfTone()
                    } catch (_: Exception) {
                    }
                }),
            Ui.lp(Ui.WRAP, Ui.WRAP)
        )
        keypadBox.visibility = View.GONE
        col.addView(keypadBox, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 6), 0, 0))

        // Кнопки разговора
        activeBox = LinearLayout(a)
        activeBox.orientation = LinearLayout.VERTICAL
        activeBox.gravity = Gravity.CENTER_HORIZONTAL
        val cs = if (sc < 0.8f) 54 else 62

        bMute = ControlButton(a, R.drawable.ic_mic_off, "Микрофон", cs)
        bMute.setOnClickListener { Calls.setMuted(!Calls.isMuted()) }
        bKeypad = ControlButton(a, R.drawable.ic_dialpad, "Клавиши", cs)
        bKeypad.setOnClickListener { toggleKeypad() }
        bSpeaker = ControlButton(a, R.drawable.ic_volume_up, "Динамик", cs)
        bSpeaker.setOnClickListener { Calls.setSpeaker(!Calls.isSpeakerOn(), a) }
        bHold = ControlButton(a, R.drawable.ic_pause, "Пауза", cs)
        bHold.setOnClickListener {
            val c = Calls.primary() ?: return@setOnClickListener
            try {
                if (c.state == Call.STATE_HOLDING) c.unhold() else c.hold()
            } catch (_: Exception) {
            }
        }
        bRecord = ControlButton(a, R.drawable.ic_mic, "Запись", cs)
        bRecord.setOnClickListener { toggleRecord() }
        bAdd = ControlButton(a, R.drawable.ic_add, "Добавить", cs)
        bAdd.setOnClickListener {
            val i = Intent(a, MainActivity::class.java)
            i.action = Intent.ACTION_DIAL
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        bHeadset = ControlButton(a, R.drawable.ic_headset, "Наушники", cs)
        bHeadset.setOnClickListener {
            val route = Calls.audioState?.route
            val kind = Calls.headsetKind(a)
            val target = if (kind == Calls.Headset.BLUETOOTH) CallAudioState.ROUTE_BLUETOOTH else CallAudioState.ROUTE_WIRED_HEADSET
            if (route == target) Calls.setRoute(CallAudioState.ROUTE_EARPIECE) else Calls.setRoute(target)
        }
        bMerge = ControlButton(a, R.drawable.ic_call, "Объединить", cs)
        bMerge.setOnClickListener { Calls.merge() }
        bSwap = ControlButton(a, R.drawable.ic_call_received, "Переключить", cs)
        bSwap.setOnClickListener { Calls.swap() }

        row1 = rowOf(bMute, bKeypad, bSpeaker)
        row2 = rowOf(bHold, bRecord, bAdd)
        row3 = rowOf(bHeadset, bMerge, bSwap)
        activeBox.addView(row1, Ui.lp(Ui.MATCH, Ui.WRAP))
        activeBox.addView(row2, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
        activeBox.addView(row3, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
        col.addView(activeBox, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 14), 0, 0))

        // Закреплённый низ: кнопки всегда на экране
        val bottom = FrameLayout(a)
        bottom.setPadding(Ui.dp(a, 24), Ui.dp(a, 14), Ui.dp(a, 24), Ui.dp(a, 18))
        bottom.clipChildren = false
        bottom.clipToPadding = false

        ringButtons = LinearLayout(a)
        ringButtons.orientation = LinearLayout.HORIZONTAL
        ringButtons.gravity = Gravity.CENTER
        ringButtons.clipChildren = false
        val decline = bigAction(R.drawable.ic_call_end, Theme.RED, "Отклонить") { Calls.reject() }
        val accept = bigAction(R.drawable.ic_call, Theme.GREEN, "Принять") { Calls.answer(a, false) }
        acceptBtn = accept.getChildAt(0)
        ringButtons.addView(decline, Ui.lp(0, Ui.WRAP, 1f))
        ringButtons.addView(accept, Ui.lp(0, Ui.WRAP, 1f))
        bottom.addView(ringButtons, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        endBox = bigAction(R.drawable.ic_call_end, Theme.RED, "") { Calls.hangup() }
        bottom.addView(endBox, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER_HORIZONTAL))
        outer.clipChildren = false
        outer.addView(bottom, Ui.lp(Ui.MATCH, Ui.WRAP))

        setContentView(root)
    }

    private fun rowOf(x: ControlButton, y: ControlButton, z: ControlButton): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER
        r.addView(x, Ui.lp(0, Ui.WRAP, 1f))
        r.addView(y, Ui.lp(0, Ui.WRAP, 1f))
        r.addView(z, Ui.lp(0, Ui.WRAP, 1f))
        return r
    }

    /** Большая круглая кнопка с подписью. Первый дочерний элемент — сама кнопка. */
    private fun bigAction(iconRes: Int, color: Int, label: String, onClick: () -> Unit): LinearLayout {
        val a = this
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.clipChildren = false
        val size = if (sc < 0.8f) 66 else 76
        val btn = Ui.roundButton(a, size, Ui.oval(color), iconRes, Color.WHITE, 34)
        btn.setOnClickListener { onClick() }
        box.addView(btn)
        if (label.isNotEmpty()) {
            box.addView(Ui.text(a, label, 14f, 0xCCFFFFFF.toInt()), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 6), 0, 0))
        }
        return box
    }

    private fun toggleKeypad() {
        val show = keypadBox.visibility != View.VISIBLE
        keypadBox.visibility = if (show) View.VISIBLE else View.GONE
        avatarFrame.visibility = if (show) View.GONE else View.VISIBLE
        row2.visibility = if (show) View.GONE else View.VISIBLE
        row3.visibility = if (show) View.GONE else row3.visibility
        numberTv.visibility = if (show || numberTv.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        bKeypad.setActive(show)
        if (!show) {
            dtmfText = ""
            dtmfTv.text = ""
        }
    }

    private fun toggleRecord() {
        if (Recorder.active) {
            Recorder.stop(this)
        } else {
            val why = Prefs.recBroken(this)
            if (why != null) {
                Toast.makeText(this, "Запись на этом телефоне недоступна: $why. Сбросить можно в настройках.", Toast.LENGTH_LONG).show()
            } else {
                val inf = Calls.info(Calls.primary())
                val err = Recorder.start(this, inf?.name ?: Calls.formatNumber(inf?.number ?: ""), inf?.number ?: "", false)
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
        }
        render()
    }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onStart() {
        super.onStart()
        started = true
        Calls.addListener(this)
        ui.removeCallbacks(timer)
        ui.post(timer)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        finishScheduled = false
        render()
    }

    override fun onStop() {
        started = false
        Calls.removeListener(this)
        ui.removeCallbacks(timer)
        voice?.stop()
        pulseAnim?.cancel()
        pulseAnim = null
        releaseProximity()
        super.onStop()
    }

    override fun onDestroy() {
        voice?.stop()
        voice = null
        releaseProximity()
        super.onDestroy()
    }

    override fun onCallsChanged() {
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Во время звонка «Назад» не закрывает экран — приложение остаётся на звонке
        if (Calls.calls.isEmpty()) super.onBackPressed() else moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ отрисовка состояния

    private fun render() {
        if (!started) return
        val call = Calls.primary()
        if (call == null) {
            statusTv.text = "Звонок завершён"
            chip.text = "Завершён"
            ringButtons.visibility = View.GONE
            endBox.visibility = View.GONE
            activeBox.visibility = View.GONE
            voiceCard.visibility = View.GONE
            quietBtn.visibility = View.GONE
            voice?.stop()
            pulse.running = false
            releaseProximity()
            if (!finishScheduled) {
                finishScheduled = true
                ui.postDelayed({
                    if (Calls.calls.isEmpty()) finishAndRemoveTask() else finishScheduled = false
                }, 800)
            }
            return
        }
        finishScheduled = false
        val info = Calls.info(call)
        nameTv.text = Calls.title(info)
        numberTv.text = Calls.subtitle(info)
        numberTv.visibility = if (keypadBox.visibility == View.VISIBLE || numberTv.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        updateAvatar(info?.name, info?.photo)

        val ringing = call.state == Call.STATE_RINGING
        val active = call.state == Call.STATE_ACTIVE
        val holding = call.state == Call.STATE_HOLDING

        when (call.state) {
            Call.STATE_RINGING -> {
                chip.text = if (Calls.calls.size > 1) "Второй вызов" else "Входящий вызов"
                statusTv.text = if (Calls.announcing) "Озвучиваю звонок…" else "звонит…"
            }
            Call.STATE_ACTIVE -> {
                chip.text = "Разговор"
                val start = call.details.connectTimeMillis
                statusTv.text = if (start > 0) Logic.formatDuration((System.currentTimeMillis() - start) / 1000) else "Разговор"
            }
            Call.STATE_HOLDING -> {
                chip.text = "На удержании"
                statusTv.text = "Пауза"
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW, Call.STATE_SELECT_PHONE_ACCOUNT -> {
                chip.text = "Исходящий вызов"
                statusTv.text = "Вызываю…"
            }
            else -> {
                chip.text = "Звонок"
                statusTv.text = "Завершение…"
            }
        }

        ringButtons.visibility = if (ringing) View.VISIBLE else View.GONE
        endBox.visibility = if (ringing) View.GONE else View.VISIBLE
        activeBox.visibility = if (ringing) View.GONE else View.VISIBLE
        if (ringing && keypadBox.visibility == View.VISIBLE) toggleKeypad()
        pulse.running = ringing
        if (ringing) pulse.postInvalidateOnAnimation()
        startAcceptPulse(ringing)

        // Кнопки разговора
        bMute.setActive(Calls.isMuted())
        bSpeaker.setActive(Calls.isSpeakerOn())
        bHold.setActive(holding)
        bHold.setLabel(if (holding) "Продолжить" else "Пауза")
        val recBroken = Prefs.recBroken(this) != null && !Recorder.active
        bRecord.setUnavailable(recBroken)
        if (!recBroken) bRecord.setActive(Recorder.active)
        bRecord.setLabel(if (recBroken) "Запись недоступна" else if (Recorder.active) "Идёт запись" else "Запись")
        val headset = Calls.headsetKind(this)
        val rt = Calls.audioState?.route
        bHeadset.visibility = if (headset == Calls.Headset.NONE) View.INVISIBLE else View.VISIBLE
        bHeadset.setActive(rt == CallAudioState.ROUTE_BLUETOOTH || rt == CallAudioState.ROUTE_WIRED_HEADSET)
        val heldCall = Calls.held()
        bSwap.visibility = if (heldCall != null) View.VISIBLE else View.INVISIBLE
        bMerge.visibility = if (Calls.canMerge()) View.VISIBLE else View.INVISIBLE
        if (keypadBox.visibility != View.VISIBLE) {
            row3.visibility = if (headset != Calls.Headset.NONE || heldCall != null || Calls.canMerge()) View.VISIBLE else View.GONE
        }
        routeTv.text = when {
            ringing -> ""
            heldCall != null -> "На удержании: " + Calls.title(Calls.info(heldCall))
            else -> Calls.routeName()
        }
        if (!ringing) {
            silenced = false
            commandSent = false
        }
        quietBtn.visibility = if (ringing && !silenced && Build.VERSION.SDK_INT >= 28) View.VISIBLE else View.GONE

        updateVoice(ringing)
        updateProximity(active || call.state == Call.STATE_DIALING)
    }

    private fun updateAvatar(name: String?, photo: String?) {
        val key = (name ?: "") + "|" + (photo ?: "")
        if (key == avatarKey) return
        avatarKey = key
        avatar.bind(name, photo)
    }

    private fun startAcceptPulse(ringing: Boolean) {
        if (!ringing) {
            pulseAnim?.cancel()
            pulseAnim = null
            acceptBtn.scaleX = 1f
            acceptBtn.scaleY = 1f
            return
        }
        if (pulseAnim != null) return
        val anim = ObjectAnimator.ofPropertyValuesHolder(
            acceptBtn,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.1f)
        )
        anim.duration = 650
        anim.repeatCount = ValueAnimator.INFINITE
        anim.repeatMode = ValueAnimator.REVERSE
        anim.start()
        pulseAnim = anim
    }

    // ------------------------------------------------------------------ голос

    private fun ensureVoice(): VoiceListener {
        val existing = voice
        if (existing != null) return existing
        val v = VoiceListener(
            this, true,
            onCommand = { cmd -> onVoiceCommand(cmd) },
            onHeard = { text -> heardTv.text = "Слышу: «$text»" },
            onLevel = { lvl -> eq.level = lvl },
            onStatus = { st -> if (st.isNotEmpty()) heardTv.text = st }
        )
        voice = v
        return v
    }

    private fun onVoiceCommand(cmd: Logic.Command) {
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
    }

    private fun updateVoice(ringing: Boolean) {
        val enabled = Prefs.voiceEnabled(this)
        val show = ringing && enabled
        voiceCard.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val a = Prefs.acceptWords(this).firstOrNull() ?: "принять"
            val r = Prefs.rejectWords(this).firstOrNull() ?: "отклонить"
            (micToggle.getChildAt(0) as ImageView).setImageResource(if (voiceOffForThisCall) R.drawable.ic_mic_off else R.drawable.ic_mic)
            voiceTitle.text = when {
                voiceOffForThisCall -> "Голос выключен"
                Calls.announcing -> "Сейчас скажу, кто звонит…"
                else -> "Скажите «$a» или «$r»"
            }
            if (voiceOffForThisCall) heardTv.text = "Нажмите на микрофон, чтобы включить"
        }
        val want = show && !voiceOffForThisCall && !Calls.announcing && !commandSent
        val v = voice
        if (want) {
            ensureVoice().start()
        } else if (v != null && v.running) {
            v.stop()
        }
        if (!want) eq.level = 0f
    }

    // ------------------------------------------------------------------ датчик приближения

    private fun updateProximity(inCall: Boolean) {
        val earpiece = Calls.audioState?.route == CallAudioState.ROUTE_EARPIECE
        val want = inCall && earpiece
        val lock = proximity ?: return
        try {
            if (want && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
            if (!want && lock.isHeld) lock.release()
        } catch (_: Exception) {
        }
    }

    private fun releaseProximity() {
        try {
            val lock = proximity
            if (lock != null && lock.isHeld) lock.release()
        } catch (_: Exception) {
        }
    }
}
