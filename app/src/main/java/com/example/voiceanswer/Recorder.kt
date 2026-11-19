@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Одна запись из журнала. */
class Rec(
    val uri: String,
    val isFile: Boolean,
    val who: String,
    val number: String,
    val startedAt: Long,
    val seconds: Int,
    val auto: Boolean,
    val micOnly: Boolean
) {
    fun toUri(): Uri = if (isFile) Uri.fromFile(File(uri)) else Uri.parse(uri)
}

/** Журнал записей лежит в файле recordings.json рядом с данными приложения. */
object RecLog {
    private fun file(c: Context) = File(c.applicationContext.filesDir, "recordings.json")

    fun load(c: Context): List<Rec> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Rec>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Rec(
                        o.getString("uri"), o.optBoolean("file", false), o.optString("who", ""), o.optString("number", ""),
                        o.optLong("at", 0L), o.optInt("sec", 0), o.optBoolean("auto", false), o.optBoolean("mic", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out.sortedByDescending { it.startedAt }
    }

    private fun save(c: Context, list: List<Rec>) {
        try {
            val arr = JSONArray()
            for (r in list) {
                val o = JSONObject()
                o.put("uri", r.uri)
                o.put("file", r.isFile)
                o.put("who", r.who)
                o.put("number", r.number)
                o.put("at", r.startedAt)
                o.put("sec", r.seconds)
                o.put("auto", r.auto)
                o.put("mic", r.micOnly)
                arr.put(o)
            }
            file(c).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun add(c: Context, r: Rec) {
        save(c, load(c) + r)
    }

    /** Удаляет запись из журнала и сам файл. */
    fun remove(c: Context, r: Rec) {
        try {
            if (r.isFile) File(r.uri).delete() else c.contentResolver.delete(r.toUri(), null, null)
        } catch (_: Exception) {
        }
        save(c, load(c).filter { it.uri != r.uri })
    }

    /** Убирает из журнала записи, чьи файлы пользователь уже удалил сам. */
    fun exists(c: Context, r: Rec): Boolean = try {
        if (r.isFile) File(r.uri).exists()
        else c.contentResolver.openFileDescriptor(r.toUri(), "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    fun prune(c: Context): List<Rec> {
        val all = load(c)
        val alive = all.filter { exists(c, it) }
        if (alive.size != all.size) save(c, alive)
        return alive
    }

    /** Копирует запись в «Загрузки» (Android 10+). Возвращает текст для пользователя. */
    fun download(c: Context, r: Rec): String {
        if (Build.VERSION.SDK_INT < 29) return "Скачивание работает на Android 10 и новее. Файл: " + r.uri
        return try {
            val name = "Звонок_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(r.startedAt)) + ".m4a"
            val v = ContentValues()
            v.put(MediaStore.Downloads.DISPLAY_NAME, name)
            v.put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Записи звонков")
            val dst = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return "Не удалось сохранить"
            c.contentResolver.openInputStream(r.toUri())?.use { input ->
                c.contentResolver.openOutputStream(dst)?.use { output -> input.copyTo(output) }
            }
            "Сохранено: Загрузки / Записи звонков"
        } catch (e: Exception) {
            "Не удалось сохранить"
        }
    }
}

/**
 * Запись разговора. Android не даёт обычным приложениям писать голос собеседника напрямую,
 * поэтому сначала пробуем «запись вызова», а если телефон не разрешает — пишем через микрофон
 * (тогда собеседника слышно, только если включена громкая связь).
 * Если телефон записывает тишину или вовсе не умеет — кнопка записи становится серой.
 */
object Recorder {
    private val handler = Handler(Looper.getMainLooper())
    private var rec: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var file: File? = null
    private var usedCallSource = false

    private var startedAtWall = 0L
    private var startedAtMono = 0L
    private var who = ""
    private var number = ""
    private var auto = false
    private var sawSound = false
    private var app: Context? = null

    val active: Boolean get() = rec != null

    /** true, если пишется только микрофон (собеседника слышно лишь на громкой связи). */
    val micOnly: Boolean get() = active && !usedCallSource

    private fun newRecorder(ctx: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    private fun safe(s: String): String = s.filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '_').take(24)

    private fun fileName(who: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val w = safe(who)
        return "Звонок_" + (if (w.isEmpty()) "" else w + "_") + stamp + ".m4a"
    }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

    private val tick = object : Runnable {
        override fun run() {
            val r = rec ?: return
            val ctx = app ?: return
            val elapsed = SystemClock.elapsedRealtime() - startedAtMono
            try {
                if (r.maxAmplitude > 0) sawSound = true
            } catch (_: Exception) {
            }
            val limit = Prefs.recMaxSeconds(ctx)
            if (limit > 0 && elapsed >= limit * 1000L) {
                stop(ctx)
                toast(ctx, "Запись остановлена по таймеру")
                return
            }
            if (!sawSound && usedCallSource && elapsed >= 6000) {
                onSilent(ctx)
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** За 8 секунд не было ни единого звука: источник не работает. */
    private fun onSilent(ctx: Context) {
        val w = who
        val n = number
        val a = auto
        discard(ctx)
        // «Запись вызова» дала тишину — переходим на микрофон
        val err = begin(ctx, w, n, a, true)
        if (err != null) toast(ctx, err)
    }

    /** В прежних версиях запись могла быть ошибочно помечена как «неработающая» — один раз сбрасываем. */
    private fun fixOldFlag(ctx: Context) {
        try {
            val sp = ctx.applicationContext.getSharedPreferences("voice_answer", Context.MODE_PRIVATE)
            if (!sp.getBoolean("rec_fix_done", false)) {
                sp.edit().putBoolean("rec_fix_done", true).remove("rec_broken").remove("rec_fail").apply()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Открывает диктофон самого телефона. Приложениям Android не даёт включать системную запись
     * разговора, поэтому голос собеседника пишется микрофоном — для этого включаем громкую связь.
     * Возвращает false, если на телефоне нет диктофона.
     */
    fun openSystemRecorder(ctx: Context): Boolean {
        val appCtx = ctx.applicationContext
        return try {
            val i = android.content.Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            if (Calls.headsetKind(appCtx) == Calls.Headset.NONE) Calls.setSpeaker(true, appCtx)
            toast(appCtx, "Открыт диктофон телефона. Нажмите там запись; громкая связь включена, чтобы записать собеседника. Вернуться к звонку — через уведомление.")
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Возвращает null, если запись началась, иначе — текст ошибки. */
    fun start(ctx: Context, who: String, number: String, auto: Boolean): String? {
        if (rec != null) return null
        fixOldFlag(ctx)
        val broken = Prefs.recBroken(ctx)
        if (broken != null) return "Запись недоступна: $broken"
        return begin(ctx, who, number, auto, false)
    }

    private fun begin(ctx: Context, whoName: String, num: String, isAuto: Boolean, forceMic: Boolean): String? {
        val appCtx = ctx.applicationContext
        app = appCtx
        if (!Perms.has(appCtx, android.Manifest.permission.RECORD_AUDIO)) return "Нет разрешения на микрофон"
        val name = fileName(whoName)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Записи звонков")
                v.put(MediaStore.Audio.Media.IS_PENDING, 1)
                val u = appCtx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
                    ?: return "Не удалось создать файл записи"
                uri = u
                pfd = appCtx.contentResolver.openFileDescriptor(u, "w")
            } else {
                val dir = File(appCtx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Записи звонков")
                dir.mkdirs()
                file = File(dir, name)
            }
        } catch (e: Exception) {
            cleanup(appCtx, false)
            return "Не удалось создать файл записи"
        }

        val sources = if (forceMic) intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        else intArrayOf(MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        var lastError = "Телефон не разрешает запись"
        for (src in sources) {
            val r = newRecorder(appCtx)
            try {
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128000)
                val p = pfd
                if (p != null) r.setOutputFile(p.fileDescriptor) else r.setOutputFile(file!!.absolutePath)
                r.prepare()
                r.start()
                rec = r
                usedCallSource = src == MediaRecorder.AudioSource.VOICE_CALL
                who = whoName
                number = num
                auto = isAuto
                sawSound = false
                startedAtWall = System.currentTimeMillis()
                startedAtMono = SystemClock.elapsedRealtime()
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 1000)
                val sp = appCtx.getSharedPreferences("voice_answer", Context.MODE_PRIVATE)
                sp.edit().putInt("rec_fail", 0).remove("rec_broken").apply()
                if (!usedCallSource && Calls.headsetKind(appCtx) == Calls.Headset.NONE) {
                    Calls.setSpeaker(true, appCtx)
                    toast(appCtx, "Запись идёт через микрофон — включена громкая связь, чтобы слышать собеседника")
                }
                return null
            } catch (e: Exception) {
                lastError = "Не удалось начать запись: " + (e.message ?: e.javaClass.simpleName)
                try {
                    r.reset()
                    r.release()
                } catch (_: Exception) {
                }
            }
        }
        cleanup(appCtx, false)
        // кнопка станет серой только если запись не стартовала три раза подряд
        val sp = appCtx.getSharedPreferences("voice_answer", Context.MODE_PRIVATE)
        val fails = sp.getInt("rec_fail", 0) + 1
        sp.edit().putInt("rec_fail", fails).apply()
        if (fails >= 3) Prefs.setRecBroken(appCtx, "телефон не даёт начать запись")
        return lastError
    }

    /** Останавливает запись, сохраняет файл и добавляет его в журнал. */
    fun stop(ctx: Context) {
        val r = rec ?: return
        val appCtx = ctx.applicationContext
        handler.removeCallbacks(tick)
        rec = null
        val seconds = ((SystemClock.elapsedRealtime() - startedAtMono) / 1000).toInt()
        var ok = true
        try {
            r.stop()
        } catch (e: Exception) {
            ok = false
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
        // даже если stop() пожаловался, но файл не пустой — сохраняем
        if (seconds < 2 || (!ok && size() <= 1024)) {
            cleanup(appCtx, false)
            return
        }
        val entry = Rec(
            if (uri != null) uri.toString() else file!!.absolutePath,
            uri == null, who, number, startedAtWall, seconds, auto, !usedCallSource
        )
        cleanup(appCtx, true)
        RecLog.add(appCtx, entry)
        toast(appCtx, "Запись сохранена (" + Logic.formatDuration(seconds.toLong()) + ")")
    }

    private fun size(): Long = try {
        val c = app
        val u = uri
        if (u != null && c != null) c.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: 0L else file?.length() ?: 0L
    } catch (e: Exception) {
        0L
    }

    /** Остановить и выбросить запись без сохранения. */
    private fun discard(ctx: Context) {
        val r = rec ?: return
        handler.removeCallbacks(tick)
        rec = null
        try {
            r.stop()
        } catch (_: Exception) {
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
        cleanup(ctx.applicationContext, false)
    }

    private fun cleanup(appCtx: Context, keep: Boolean) {
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        val u = uri
        if (u != null) {
            try {
                if (keep && Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues()
                    v.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    appCtx.contentResolver.update(u, v, null, null)
                } else if (!keep) {
                    appCtx.contentResolver.delete(u, null, null)
                }
            } catch (_: Exception) {
            }
        }
        uri = null
        val f = file
        if (f != null && !keep) {
            try {
                f.delete()
            } catch (_: Exception) {
            }
        }
        file = null
    }
}
