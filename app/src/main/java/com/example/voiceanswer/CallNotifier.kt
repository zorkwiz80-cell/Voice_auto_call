@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.telecom.Call

/** Уведомления о звонках: входящий (с кнопками), идущий разговор, пропущенный. */
object CallNotifier {
    private const val CH_INCOMING = "calls_incoming"
    private const val CH_INCOMING_QUIET = "calls_incoming_popup"
    private const val CH_ONGOING = "calls_ongoing"
    private const val CH_MISSED = "calls_missed"
    private const val ID_CALL = 101
    private const val ID_MISSED = 102

    private fun channels(ctx: Context, nm: NotificationManager) {
        val incoming = NotificationChannel(CH_INCOMING, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH)
        incoming.setSound(null, null) // рингтон играет система
        incoming.enableVibration(false)
        incoming.setShowBadge(false)
        incoming.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(incoming)

        // В режиме «окно сверху» уведомление не всплывает (всплывает наше окно)
        val quiet = NotificationChannel(CH_INCOMING_QUIET, "Входящие (окно сверху)", NotificationManager.IMPORTANCE_LOW)
        quiet.setSound(null, null)
        quiet.enableVibration(false)
        quiet.setShowBadge(false)
        quiet.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(quiet)

        val ongoing = NotificationChannel(CH_ONGOING, "Идёт разговор", NotificationManager.IMPORTANCE_LOW)
        ongoing.setShowBadge(false)
        nm.createNotificationChannel(ongoing)

        val missed = NotificationChannel(CH_MISSED, "Пропущенные звонки", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(missed)
    }

    private fun openUi(ctx: Context): PendingIntent {
        val i = Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(ctx: Context, act: String, code: Int): PendingIntent {
        val i = Intent(ctx, CallActionReceiver::class.java).setAction(act)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun act(ctx: Context, res: Int, label: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(ctx, res), label, pi).build()

    fun update(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val call = Calls.primary()
            if (call == null) {
                nm.cancel(ID_CALL)
                return
            }
            channels(ctx, nm)
            val info = Calls.info(call)
            val title = Calls.title(info)
            val ringing = call.state == Call.STATE_RINGING

            val popup = ringing && Calls.popupNow(ctx)
            val b = when {
                popup -> Notification.Builder(ctx, CH_INCOMING_QUIET)
                ringing -> Notification.Builder(ctx, CH_INCOMING)
                else -> Notification.Builder(ctx, CH_ONGOING)
            }
            b.setSmallIcon(R.drawable.ic_call)
            b.setContentTitle(title)
            b.setCategory(Notification.CATEGORY_CALL)
            b.setOngoing(true)
            b.setContentIntent(openUi(ctx))
            b.setVisibility(Notification.VISIBILITY_PUBLIC)
            if (ringing) {
                b.setContentText("Входящий вызов")
                if (!popup) b.setFullScreenIntent(openUi(ctx), true)
                b.addAction(act(ctx, R.drawable.ic_call_end, "Отклонить", action(ctx, CallActionReceiver.ACTION_DECLINE, 1)))
                b.addAction(act(ctx, R.drawable.ic_call, "Принять", action(ctx, CallActionReceiver.ACTION_ANSWER, 2)))
            } else {
                b.setContentText(if (call.state == Call.STATE_ACTIVE) "Идёт разговор" else "Вызов…")
                val start = call.details.connectTimeMillis
                if (call.state == Call.STATE_ACTIVE && start > 0) {
                    b.setUsesChronometer(true)
                    b.setWhen(start)
                    b.setShowWhen(true)
                }
                b.addAction(act(ctx, R.drawable.ic_call_end, "Завершить", action(ctx, CallActionReceiver.ACTION_HANGUP, 3)))
            }
            nm.notify(ID_CALL, b.build())
        } catch (_: Exception) {
        }
    }

    fun missed(ctx: Context, who: String, number: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            channels(ctx, nm)
            val open = PendingIntent.getActivity(
                ctx, 5, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = Notification.Builder(ctx, CH_MISSED)
                .setSmallIcon(R.drawable.ic_call_missed)
                .setContentTitle("Пропущенный вызов")
                .setContentText(who)
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .setContentIntent(open)
            nm.notify(ID_MISSED, b.build())
        } catch (_: Exception) {
        }
    }
}
