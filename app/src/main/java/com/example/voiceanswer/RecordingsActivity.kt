@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Date

/** Журнал записей: когда, с кем, сколько секунд, авто или вручную; слушать, скачать, поделиться, удалить. */
class RecordingsActivity : BaseActivity() {

    private var player: MediaPlayer? = null
    private var playing: Rec? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var playerBar: LinearLayout
    private lateinit var playerTitle: TextView
    private lateinit var playerTime: TextView
    private lateinit var playerToggle: TextView
    private lateinit var seek: SeekBar
    private lateinit var listBox: LinearLayout
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
        reload()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    override fun onPause() {
        stopPlayback()
        super.onPause()
    }

    private fun build() {
        val a = this
        val root = FrameLayout(a)
        val scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, 0, Ui.dp(a, 120))
        scroll.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.roundButton(a, 46, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_arrow_back, Theme.text, 24)
        back.setOnClickListener { finish() }
        top.addView(back)
        top.addView(Ui.light(a, "Журнал записей", 30f, Theme.text), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 6), 0, 0, 0))
        col.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 10), Ui.dp(a, 8), 0, Ui.dp(a, 8)))

        listBox = LinearLayout(a)
        listBox.orientation = LinearLayout.VERTICAL
        col.addView(listBox, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        // мини-плеер внизу
        playerBar = LinearLayout(a)
        playerBar.orientation = LinearLayout.VERTICAL
        playerBar.setPadding(Ui.dp(a, 18), Ui.dp(a, 12), Ui.dp(a, 18), Ui.dp(a, 14))
        playerBar.background = Ui.rounded(Theme.card2, Ui.dpf(a, 24f))
        playerBar.visibility = View.GONE
        val row = LinearLayout(a)
        row.gravity = Gravity.CENTER_VERTICAL
        playerToggle = Ui.text(a, "❚❚", 22f, Theme.text, true)
        playerToggle.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6))
        playerToggle.setOnClickListener { togglePause() }
        row.addView(playerToggle)
        playerTitle = Ui.text(a, "", 15f, Theme.text, true)
        playerTitle.maxLines = 1
        playerTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(playerTitle, Ui.lp(0, Ui.WRAP, 1f))
        playerTime = Ui.text(a, "0:00", 13f, Theme.sub)
        row.addView(playerTime)
        playerBar.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        seek = SeekBar(a)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) playerTime.text = Logic.formatDuration((p / 1000).toLong())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                try {
                    player?.seekTo(seek.progress)
                } catch (_: Exception) {
                }
            }
        })
        onAccent { c ->
            seek.progressTintList = android.content.res.ColorStateList.valueOf(c)
            seek.thumbTintList = android.content.res.ColorStateList.valueOf(c)
        }
        playerBar.addView(seek, Ui.lp(Ui.MATCH, Ui.WRAP))
        val lp = FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM)
        lp.setMargins(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 12))
        root.addView(playerBar, lp)
        setContentView(root)
    }

    private fun reload() {
        val a = this
        listBox.removeAllViews()
        val items = RecLog.prune(a)
        if (items.isEmpty()) {
            val t = Ui.text(
                a,
                "Записей пока нет.\n\nВключите «Автозапись» в настройках или нажмите кнопку «Запись» во время разговора.",
                16f, Theme.sub
            )
            t.setPadding(Ui.dp(a, 24), Ui.dp(a, 24), Ui.dp(a, 24), 0)
            listBox.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP))
            return
        }
        for (r in items) listBox.addView(rowFor(r))
    }

    private fun whenText(t: Long): String =
        DateFormat.format("d MMMM yyyy, HH:mm:ss", Date(t)).toString()

    private fun rowFor(r: Rec): View {
        val a = this
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14))
        box.background = Ui.ripple(Ui.rounded(Theme.card, Ui.dpf(a, 22f)), Ui.dpf(a, 22f))
        box.isClickable = true
        val who = if (r.who.isNotBlank()) r.who else if (r.number.isNotBlank()) r.number else "Неизвестный"
        val isPlaying = playing?.uri == r.uri
        box.addView(Ui.text(a, (if (isPlaying) "▶ " else "") + who, 17f, Theme.text, true), Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(Ui.text(a, whenText(r.startedAt), 14f, Theme.sub), Ui.lp(Ui.MATCH, Ui.WRAP))
        val kind = (if (r.auto) "Автозапись" else "Вручную") + (if (r.micOnly) " · через микрофон" else "")
        box.addView(
            Ui.text(a, "${r.seconds} сек (${Logic.formatDuration(r.seconds.toLong())}) · $kind", 14f, Theme.sub),
            Ui.lp(Ui.MATCH, Ui.WRAP)
        )
        val btns = LinearLayout(a)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.addView(small(if (isPlaying) "Стоп" else "Слушать", true) { if (isPlaying) stopPlayback().also { reload() } else play(r) })
        btns.addView(small("Скачать", false) { Toast.makeText(a, RecLog.download(a, r), Toast.LENGTH_LONG).show() })
        btns.addView(small("Ещё", false) { more(r) })
        box.addView(btns, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
        box.setOnClickListener { play(r) }
        val lp = Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), 0)
        box.layoutParams = lp
        return box
    }

    private fun small(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val a = this
        val b = Ui.text(a, label, 14f, if (primary) Color.WHITE else Theme.text, primary)
        b.gravity = Gravity.CENTER
        b.setPadding(Ui.dp(a, 16), Ui.dp(a, 9), Ui.dp(a, 16), Ui.dp(a, 9))
        val fill = if (primary) Ui.accentFill(Ui.dpf(a, 20f)) else Ui.rounded(Theme.card2, Ui.dpf(a, 20f))
        b.background = Ui.ripple(fill, Ui.dpf(a, 20f))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        b.layoutParams = Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(a, 8), 0)
        return b
    }

    private fun more(r: Rec) {
        val a = this
        val acts = ArrayList<Triple<Int, String, () -> Unit>>()
        if (!r.isFile) {
            acts.add(Triple(R.drawable.ic_message, "Поделиться") {
                try {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "audio/mp4"
                    i.putExtra(Intent.EXTRA_STREAM, r.toUri())
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Поделиться записью"))
                } catch (e: Exception) {
                    Toast.makeText(a, "Не удалось поделиться", Toast.LENGTH_SHORT).show()
                }
            })
        }
        if (r.number.isNotBlank()) {
            acts.add(Triple(R.drawable.ic_call, "Позвонить") {
                Perms.open(a, Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", r.number, null)))
            })
        }
        acts.add(Triple(R.drawable.ic_delete, "Удалить запись") {
            if (playing?.uri == r.uri) stopPlayback()
            RecLog.remove(a, r)
            reload()
        })
        Ui.sheet(a, if (r.who.isNotBlank()) r.who else "Запись", whenText(r.startedAt), acts)
    }

    // ---- проигрывание ----

    private fun play(r: Rec) {
        val a = this
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
            mp.setDataSource(a, r.toUri())
            mp.setOnCompletionListener {
                stopPlayback()
                reload()
            }
            mp.prepare()
            mp.start()
            player = mp
            playing = r
            seek.max = mp.duration
            seek.progress = 0
            playerTitle.text = if (r.who.isNotBlank()) r.who else r.number
            playerToggle.text = "❚❚"
            playerBar.visibility = View.VISIBLE
            handler.post(ticker)
        } catch (e: Exception) {
            stopPlayback()
            Toast.makeText(a, "Не удалось воспроизвести запись", Toast.LENGTH_LONG).show()
        }
        reload()
    }

    private fun togglePause() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                playerToggle.text = "▶"
            } else {
                mp.start()
                playerToggle.text = "❚❚"
                handler.post(ticker)
            }
        } catch (_: Exception) {
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val mp = player ?: return
            try {
                if (!dragging) {
                    seek.progress = mp.currentPosition
                    playerTime.text = Logic.formatDuration((mp.currentPosition / 1000).toLong())
                }
                if (mp.isPlaying) handler.postDelayed(this, 300)
            } catch (_: Exception) {
            }
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacks(ticker)
        val mp = player
        player = null
        playing = null
        if (mp != null) {
            try {
                mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        if (::playerBar.isInitialized) playerBar.visibility = View.GONE
    }
}
