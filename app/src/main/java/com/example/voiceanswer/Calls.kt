@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils

/**
 * Сердце приложения: список текущих звонков, ответ/отбой, маршрут звука (динамик / наушники),
 * озвучка звонящего. Живёт, пока система держит CallService (то есть только во время звонка).
 */
object Calls {

    enum class Headset { NONE, WIRED, BLUETOOTH }

    class Info(val call: Call) {
        var number = ""
        var name: String? = null
        var photo: String? = null
        var wasRinging = false
        var answeredByVoice = false
        var answeredHere = false
        var route: Int? = null
        var maxVolume = false
        var started = false
    }

    interface Listener {
        fun onCallsChanged()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val infos = HashMap<Call, Info>()
    val calls = ArrayList<Call>()

    var service: InCallService? = null
    var audioState: CallAudioState? = null
    var announcing = false
        private set
    private var announcer: Announcer? = null
    private var app: Context? = null

    // ------------------------------------------------------------------ подписчики

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        for (l in ArrayList(listeners)) l.onCallsChanged()
    }

    // ------------------------------------------------------------------ выбор «главного» звонка

    fun ringing(): Call? = calls.firstOrNull { it.state == Call.STATE_RINGING }

    fun primary(): Call? {
        val r = ringing()
        if (r != null) return r
        val a = calls.firstOrNull {
            it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING
        }
        if (a != null) return a
        val h = calls.firstOrNull { it.state == Call.STATE_HOLDING }
        if (h != null) return h
        return calls.firstOrNull()
    }

    fun info(call: Call?): Info? = if (call == null) null else infos[call]

    fun title(i: Info?): String = when {
        i == null -> ""
        !i.name.isNullOrBlank() -> i.name!!
        i.number.isNotBlank() -> formatNumber(i.number)
        else -> "Неизвестный номер"
    }

    fun subtitle(i: Info?): String =
        if (i != null && !i.name.isNullOrBlank() && i.number.isNotBlank()) formatNumber(i.number) else ""

    fun formatNumber(n: String): String = try {
        PhoneNumberUtils.formatNumber(n, "RU") ?: n
    } catch (e: Exception) {
        n
    }

    // ------------------------------------------------------------------ события от CallService

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val info = infos[call] ?: return
            if (state == Call.STATE_ACTIVE) onActive(call, info)
            if (state != Call.STATE_RINGING) {
                stopAnnounce()
                if (calls.none { it.state == Call.STATE_RINGING }) Ringer.stop()
            }
            refreshInfo(info)
            app?.let { CallNotifier.update(it) }
            notifyChanged()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            infos[call]?.let { refreshInfo(it) }
            app?.let { CallNotifier.update(it) }
            notifyChanged()
        }
    }

    fun onAdded(svc: InCallService, call: Call) {
        service = svc
        val ctx = svc.applicationContext
        app = ctx
        val info = Info(call)
        refreshInfo(info)
        infos[call] = info
        calls.add(call)
        call.registerCallback(callback)
        if (call.state == Call.STATE_RINGING) onRinging(ctx, info)
        CallNotifier.update(ctx)
        launchUi(ctx)
        notifyChanged()
    }

    fun onRemoved(svc: InCallService, call: Call) {
        val ctx = svc.applicationContext
        val info = infos.remove(call)
        restoreRingVolume(ctx)
        try {
            call.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        calls.remove(call)
        if (info != null && info.started) Haptics.callEnded(ctx)
        if (info != null && info.wasRinging && !info.answeredHere) {
            val cause = try {
                call.details.disconnectCause?.code
            } catch (e: Exception) {
                null
            }
            if (cause == DisconnectCause.MISSED) CallNotifier.missed(ctx, title(info), info.number)
        }
        if (calls.none { it.state == Call.STATE_RINGING }) {
            stopAnnounce()
            Ringer.stop()
        }
        CallNotifier.update(ctx)
        notifyChanged()
    }

    fun onAudioState(state: CallAudioState?) {
        audioState = state
        notifyChanged()
    }

    fun onServiceDestroyed() {
        service = null
        audioState = null
        stopAnnounce()
        Ringer.stop()
    }

    private fun refreshInfo(info: Info) {
        val ctx = app ?: return
        try {
            val d = info.call.details
            val number = d.handle?.schemeSpecificPart ?: ""
            if (number != info.number || info.name == null) {
                info.number = number
                val found = lookupContact(ctx, number)
                info.name = found.first
                info.photo = found.second
            }
            if (info.name == null) {
                val shown = d.callerDisplayName
                if (!shown.isNullOrBlank()) info.name = shown
            }
        } catch (_: Exception) {
        }
    }

    /** Имя и фото контакта по номеру (null, если такого контакта нет). */
    fun lookupContact(ctx: Context, number: String): Pair<String?, String?> {
        if (number.isBlank()) return Pair(null, null)
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return Pair(null, null)
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val proj = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
            ctx.contentResolver.query(uri, proj, null, null, null)?.use {
                if (it.moveToFirst()) Pair(it.getString(0), it.getString(1)) else Pair(null, null)
            } ?: Pair(null, null)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    fun lookupName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Показывать входящий небольшим окном сверху: выбран такой режим, телефон разблокирован, звонок один. */
    fun popupNow(ctx: Context): Boolean {
        if (Prefs.callMode(ctx) != "popup" || calls.size > 1) return false
        return try {
            val km = ctx.getSystemService(KeyguardManager::class.java)
            val pm = ctx.getSystemService(PowerManager::class.java)
            !km.isKeyguardLocked && pm.isInteractive
        } catch (e: Exception) {
            false
        }
    }

    fun launchUi(ctx: Context) {
        try {
            val cls = if (ringing() != null && popupNow(ctx)) InCallPopupActivity::class.java else InCallActivity::class.java
            val i = Intent(ctx, cls)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
            // Окно сверху не открылось — пробуем обычный экран звонка
            try {
                val i = Intent(ctx, InCallActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ входящий: озвучка

    private fun onRinging(ctx: Context, info: Info) {
        info.wasRinging = true
        // Звонок проигрываем сами (нарастающая громкость); при втором звонке во время разговора — без мелодии
        if (calls.size <= 1) {
            // На некоторых телефонах система всё равно играет свой рингтон на полной громкости —
            // заглушаем его (несколько раз, пока звонок идёт) и проигрываем свой, нарастающий
            silenceRinger(ctx)
            for (delay in longArrayOf(250, 800, 2000)) {
                handler.postDelayed({
                    if (calls.any { it.state == Call.STATE_RINGING }) silenceRinger(ctx)
                }, delay)
            }
            Ringer.start(ctx, !info.name.isNullOrBlank(), Ringer.customFor(ctx, info.number))
        }
        if (Prefs.announce(ctx)) {
            announcing = true
            val a = announcer ?: Announcer(ctx).also { announcer = it }
            a.speak(announceText(info)) {
                if (announcing) {
                    announcing = false
                    notifyChanged()
                }
            }
            // Страховка: если озвучка зависла, всё равно начинаем слушать
            handler.postDelayed({
                if (announcing) {
                    announcing = false
                    notifyChanged()
                }
            }, 9000)
        }
    }

    /** Подготовить озвучку заранее, чтобы первый звонок не ждал её запуска. */
    fun warmUp(ctx: Context) {
        if (announcer == null && Prefs.announce(ctx)) announcer = Announcer(ctx.applicationContext)
    }

    fun announceText(info: Info): String {
        val name = info.name
        val who = when {
            !name.isNullOrBlank() -> name
            info.number.isNotBlank() -> "номер " + Logic.spellNumber(info.number)
            else -> "неизвестный номер"
        }
        return "Звонит $who"
    }

    fun stopAnnounce() {
        if (announcing) {
            announcing = false
            announcer?.stop()
            notifyChanged()
        }
    }

    /** Тихий рингтон: при входящем звонке громкость мелодии ставится на 50%. */
    private var prevRing = -1

    /** После звонка возвращаем прежнюю громкость мелодии. */
    private fun restoreRingVolume(ctx: Context) {
        if (prevRing < 0) return
        val v = prevRing
        prevRing = -1
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) am.setStreamVolume(AudioManager.STREAM_RING, v, 0)
        } catch (_: Exception) {
        }
    }

    private fun setRingVolumeHalf(ctx: Context) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            if (prevRing < 0) prevRing = am.getStreamVolume(AudioManager.STREAM_RING)
            am.setStreamVolume(AudioManager.STREAM_RING, maxOf(1, max / 2), 0)
        } catch (_: Exception) {
        }
    }

    private fun silenceRinger(ctx: Context) {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            ctx.getSystemService(TelecomManager::class.java).silenceRinger()
        } catch (_: Exception) {
        }
    }

    fun silence(ctx: Context) {
        Ringer.stop()
        silenceRinger(ctx)
    }

    // ------------------------------------------------------------------ ответ, отбой

    fun answer(ctx: Context, byVoice: Boolean) {
        val call = ringing() ?: return
        val info = infos[call] ?: return
        stopAnnounce()
        Ringer.stop()
        info.answeredHere = true
        info.answeredByVoice = byVoice

        val wantSpeaker = if (byVoice) Prefs.speakerOnVoice(ctx) else Prefs.speakerOnButton(ctx)
        val kind = headsetKind(ctx)
        if (kind != Headset.NONE && Prefs.headsetFirst(ctx)) {
            // Наушники подключены — говорим через них, динамик не включаем
            info.route = if (kind == Headset.BLUETOOTH) CallAudioState.ROUTE_BLUETOOTH else CallAudioState.ROUTE_WIRED_HEADSET
            info.maxVolume = false
        } else if (wantSpeaker) {
            info.route = CallAudioState.ROUTE_SPEAKER
            info.maxVolume = Prefs.maxVolume(ctx)
        }
        try {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (_: Exception) {
        }
    }

    fun reject() {
        val call = ringing() ?: return
        stopAnnounce()
        Ringer.stop()
        try {
            call.reject(false, null)
        } catch (_: Exception) {
            try {
                call.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun hangup() {
        val call = primary() ?: return
        try {
            if (call.state == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
        } catch (_: Exception) {
        }
    }

    /** Чёткий голос: микрофон включён и шумоподавление (где телефон его поддерживает). */
    private fun clearVoice(ctx: Context) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.isMicrophoneMute = false
            for (p in listOf("noise_suppression=on", "ns_enable=1", "voice_ns=on", "fluence=on")) {
                try {
                    am.setParameters(p)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun onActive(call: Call, info: Info) {
        if (!info.started) {
            info.started = true
            app?.let { Haptics.callStarted(it) }
        }
        app?.let { restoreRingVolume(it) }
        app?.let { if (Prefs.clearVoice(it)) clearVoice(it) }
        val route = info.route ?: return
        // Аудио-режим переключается не мгновенно — несколько попыток подряд
        for (delay in longArrayOf(0, 350, 900, 1800)) {
            handler.postDelayed({
                if (infos.containsKey(call) && call.state == Call.STATE_ACTIVE) {
                    setRoute(route)
                    if (info.maxVolume) maxVoiceVolume()
                }
            }, delay)
        }
    }

    // ------------------------------------------------------------------ несколько звонков

    /** Звонок на удержании, пока идёт другой. */
    fun held(): Call? {
        val p = primary()
        return calls.firstOrNull { it != p && it.state == Call.STATE_HOLDING }
    }

    fun otherCall(): Call? {
        val p = primary()
        return calls.firstOrNull { it != p }
    }

    /** Переключиться на звонок, который стоит на удержании (текущий уйдёт на удержание сам). */
    fun swap() {
        try {
            held()?.unhold()
        } catch (_: Exception) {
        }
    }

    fun canMerge(): Boolean {
        val p = primary() ?: return false
        return try {
            calls.size >= 2 && p.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)
        } catch (e: Exception) {
            false
        }
    }

    fun merge() {
        val p = primary() ?: return
        val o = otherCall() ?: return
        try {
            p.conference(o)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ звук

    fun setRoute(route: Int) {
        try {
            service?.setAudioRoute(route)
        } catch (_: Exception) {
        }
    }

    fun setSpeaker(on: Boolean, ctx: Context) {
        if (on) {
            setRoute(CallAudioState.ROUTE_SPEAKER)
            if (Prefs.maxVolume(ctx)) maxVoiceVolume(ctx)
        } else {
            setRoute(bestQuietRoute())
        }
    }

    /** Куда вернуть звук, когда динамик выключили: Bluetooth, если есть, иначе проводные наушники/трубка. */
    fun bestQuietRoute(): Int {
        val mask = audioState?.supportedRouteMask ?: 0
        return if (mask and CallAudioState.ROUTE_BLUETOOTH != 0) CallAudioState.ROUTE_BLUETOOTH
        else CallAudioState.ROUTE_WIRED_OR_EARPIECE
    }

    fun isSpeakerOn(): Boolean = audioState?.route == CallAudioState.ROUTE_SPEAKER

    fun isMuted(): Boolean = audioState?.isMuted == true

    fun setMuted(m: Boolean) {
        try {
            service?.setMuted(m)
        } catch (_: Exception) {
        }
    }

    fun routeName(): String = when (audioState?.route) {
        CallAudioState.ROUTE_SPEAKER -> "Динамик"
        CallAudioState.ROUTE_BLUETOOTH -> "Bluetooth"
        CallAudioState.ROUTE_WIRED_HEADSET -> "Наушники"
        CallAudioState.ROUTE_EARPIECE -> "Телефон"
        else -> ""
    }

    private fun maxVoiceVolume(ctx: Context? = app) {
        val c = ctx ?: return
        try {
            val am = c.getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        } catch (_: Exception) {
        }
    }

    fun headsetKind(ctx: Context): Headset {
        val mask = audioState?.supportedRouteMask ?: 0
        if (mask and CallAudioState.ROUTE_BLUETOOTH != 0) return Headset.BLUETOOTH
        if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) return Headset.WIRED
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            var kind = Headset.NONE
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> if (kind == Headset.NONE) kind = Headset.WIRED
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> kind = Headset.BLUETOOTH
                }
            }
            kind
        } catch (e: Exception) {
            Headset.NONE
        }
    }
}
