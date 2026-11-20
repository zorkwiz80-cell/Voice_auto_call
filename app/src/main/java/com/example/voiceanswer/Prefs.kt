package com.example.voiceanswer

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения. Читаются при каждом звонке, поэтому изменения действуют сразу. */
object Prefs {
    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("voice_answer", Context.MODE_PRIVATE)

    private fun flag(c: Context, key: String, def: Boolean) = p(c).getBoolean(key, def)
    private fun setFlag(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean(key, v).apply()

    // ---- Голос и звук ----
    const val K_VOICE = "voice"
    const val K_SPEAKER_VOICE = "speaker_voice"
    const val K_SPEAKER_BUTTON = "speaker_button"
    const val K_MAX_VOLUME = "max_volume"
    const val K_HEADSET_FIRST = "headset_first"
    const val K_HEADSET_MIC = "headset_mic"
    const val K_ANNOUNCE = "announce"
    const val K_QUIET_RINGER = "quiet_ringer"
    const val K_WAKE = "wake_on"
    const val K_WAKE_MEDIA = "wake_media"
    const val K_CLEAR_VOICE = "clear_voice"
    const val K_RAMP = "ramp_ring"
    const val K_VIBRATE_CALL = "vibrate_call"

    fun vibrateCall(c: Context) = flag(c, K_VIBRATE_CALL, true)
    fun voiceEnabled(c: Context) = flag(c, K_VOICE, true)
    fun speakerOnVoice(c: Context) = flag(c, K_SPEAKER_VOICE, true)
    fun speakerOnButton(c: Context) = flag(c, K_SPEAKER_BUTTON, false)
    fun maxVolume(c: Context) = false // функция убрана
    fun headsetFirst(c: Context) = flag(c, K_HEADSET_FIRST, true)
    fun headsetMic(c: Context) = flag(c, K_HEADSET_MIC, true)
    fun announce(c: Context) = flag(c, K_ANNOUNCE, true)
    fun quietRinger(c: Context) = flag(c, K_QUIET_RINGER, false)
    fun beepMuted(c: Context): Set<Int> =
        (p(c).getString("beep_muted", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.toSet()

    fun setBeepMuted(c: Context, s: Set<Int>) = p(c).edit().putString("beep_muted", s.joinToString(",")).apply()

    fun pipeBroken(c: Context): Boolean = p(c).getBoolean("wake_pipe_broken", false)
    fun setPipeBroken(c: Context, v: Boolean) = p(c).edit().putBoolean("wake_pipe_broken", v).apply()

    fun wakeDuringMedia(c: Context): Boolean = p(c).getBoolean("wake_media", false)

    fun wakeOn(c: Context) = flag(c, K_WAKE, false)
    fun clearVoice(c: Context) = flag(c, K_CLEAR_VOICE, true)
    fun wakeWord(c: Context): String = p(c).getString("wake_word", null)?.takeIf { it.isNotBlank() } ?: "абобус"
    fun setWakeWord(c: Context, w: String) = p(c).edit().putString("wake_word", Logic.cleanWord(w)).apply()


    fun get(c: Context, key: String): Boolean = when (key) {
        K_VOICE -> voiceEnabled(c)
        K_SPEAKER_VOICE -> speakerOnVoice(c)
        K_SPEAKER_BUTTON -> speakerOnButton(c)
        K_MAX_VOLUME -> maxVolume(c)
        K_HEADSET_FIRST -> headsetFirst(c)
        K_HEADSET_MIC -> headsetMic(c)
        K_ANNOUNCE -> announce(c)
        K_QUIET_RINGER -> quietRinger(c)
        K_WAKE -> wakeOn(c)
        K_WAKE_MEDIA -> wakeDuringMedia(c)
        K_CLEAR_VOICE -> clearVoice(c)
        K_RAMP -> rampRing(c)
        K_VIBRATE_CALL -> vibrateCall(c)
        else -> false
    }

    fun set(c: Context, key: String, v: Boolean) = setFlag(c, key, v)

    // ---- Слова-команды ----
    private const val K_ACCEPT_WORDS = "accept_words"
    private const val K_REJECT_WORDS = "reject_words"

    private fun words(c: Context, key: String, def: List<String>): List<String> {
        val s = p(c).getString(key, null) ?: return def
        return s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun saveWords(c: Context, key: String, list: List<String>) {
        p(c).edit().putString(key, list.joinToString("\n")).apply()
    }

    fun acceptWords(c: Context): List<String> = words(c, K_ACCEPT_WORDS, Logic.DEFAULT_ACCEPT)
    fun rejectWords(c: Context): List<String> = words(c, K_REJECT_WORDS, Logic.DEFAULT_REJECT)

    /** Добавляет слово. false — если слово пустое или уже есть. */
    fun addWord(c: Context, accept: Boolean, raw: String): Boolean {
        val w = Logic.cleanWord(raw)
        if (w.isEmpty()) return false
        val key = if (accept) K_ACCEPT_WORDS else K_REJECT_WORDS
        val list = if (accept) acceptWords(c) else rejectWords(c)
        if (list.any { Logic.normalize(it) == w }) return false
        saveWords(c, key, list + w)
        return true
    }

    fun removeWord(c: Context, accept: Boolean, word: String) {
        val key = if (accept) K_ACCEPT_WORDS else K_REJECT_WORDS
        val list = if (accept) acceptWords(c) else rejectWords(c)
        saveWords(c, key, list.filter { it != word })
    }

    fun resetWords(c: Context) {
        p(c).edit().remove(K_ACCEPT_WORDS).remove(K_REJECT_WORDS).apply()
    }

    // ---- Оформление ----
    fun accentId(c: Context): String = p(c).getString("accent", "blue") ?: "blue"
    fun hue(c: Context): Int = p(c).getInt("hue", 280)
    fun bgMode(c: Context): String = p(c).getString("bg", "black") ?: "black"
    fun setAccent(c: Context, id: String) = p(c).edit().putString("accent", id).apply()
    fun setHue(c: Context, h: Int) = p(c).edit().putInt("hue", h).apply()
    fun setBg(c: Context, m: String) = p(c).edit().putString("bg", m).apply()

    /** Как показывать входящий: "full" — на весь экран, "popup" — окно сверху (на разблокированном телефоне). */
    fun callMode(c: Context): String = p(c).getString("call_mode", "full") ?: "full"
    fun setCallMode(c: Context, m: String) = p(c).edit().putString("call_mode", m).apply()

    /** Нарастающий рингтон: громкость плавно растёт от тихой до полной. */
    fun rampRing(c: Context): Boolean = p(c).getBoolean("ramp_ring", true)
    fun setRampRing(c: Context, v: Boolean) = p(c).edit().putBoolean("ramp_ring", v).apply()
    fun rampSeconds(c: Context): Int = p(c).getInt("ramp_sec", 12)
    fun setRampSeconds(c: Context, s: Int) = p(c).edit().putInt("ramp_sec", s).apply()

    /** Фоновая тема: none, newyear, winter, summer, spring, autumn, custom. */
    fun bgTheme(c: Context): String = p(c).getString("bg_theme", "none") ?: "none"
    fun setBgTheme(c: Context, id: String) = p(c).edit().putString("bg_theme", id).apply()

    /** Прозрачность панелей приложения в процентах (0 — непрозрачные, максимум 70). */
    fun transparency(c: Context): Int = p(c).getInt("transparency", 0).coerceIn(0, 70)
    fun setTransparency(c: Context, v: Int) = p(c).edit().putInt("transparency", v.coerceIn(0, 70)).apply()

    /** Номер версии собственной картинки фона — нужен, чтобы приложение заметило замену. */
    fun customBgVersion(c: Context): Long = p(c).getLong("custom_bg_ver", 0L)
    fun setCustomBgVersion(c: Context, v: Long) = p(c).edit().putLong("custom_bg_ver", v).apply()

    /** Фон экрана звонка: "dark" (тёмный с оттенком темы), "black", "bright" (яркий цвет темы). */
    fun callBg(c: Context): String = p(c).getString("call_bg", "dark") ?: "dark"
    fun setCallBg(c: Context, m: String) = p(c).edit().putString("call_bg", m).apply()
}
