package com.example.voiceanswer

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast

/** Разрешения и роль «телефон по умолчанию». */
object Perms {

    fun all(): List<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    fun has(c: Context, p: String): Boolean = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun missing(c: Context): List<String> = all().filter { !has(c, it) }

    fun isDefaultDialer(c: Context): Boolean = try {
        c.getSystemService(TelecomManager::class.java).defaultDialerPackage == c.packageName
    } catch (e: Exception) {
        false
    }

    fun requestDefaultDialer(a: Activity, code: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = a.getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    if (!rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), code)
                    }
                    return
                }
            }
            val i = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, a.packageName)
            a.startActivityForResult(i, code)
        } catch (e: Exception) {
            open(a, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    fun appDetails(a: Activity) {
        open(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
    }

    fun open(a: Activity, i: Intent) {
        try {
            a.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(a, "Не удалось открыть настройки на этом телефоне. Найдите их вручную.", Toast.LENGTH_LONG).show()
        }
    }
}
