package com.example.voiceanswer

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as P
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LogEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val label: String
) {
    val missed: Boolean get() = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
    val outgoing: Boolean get() = type == CallLog.Calls.OUTGOING_TYPE
    val title: String get() = if (!name.isNullOrBlank()) name else if (number.isNotBlank()) Calls.formatNumber(number) else "Неизвестный номер"
}

class PhoneNum(val dataId: Long, val number: String, val label: String)

/** Один человек: все контакты с одинаковым именем склеены, все их номера — в [numbers]. */
class Contact(
    val id: Long,
    val lookupKey: String?,
    val name: String,
    val number: String,
    val starred: Boolean,
    val photo: String?,
    val dataId: Long,
    val numbers: List<PhoneNum> = emptyList(),
    val ids: List<Long> = listOf(id)
)

/** Чтение журнала звонков и контактов (нужны разрешения; без них просто пустые списки). */
object Repo {

    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun callLog(c: Context, limit: Int = 300): List<LogEntry> {
        if (!has(c, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<LogEntry>()
        try {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "date DESC")?.use { cur ->
                val iId = cur.getColumnIndex(CallLog.Calls._ID)
                val iNum = cur.getColumnIndex(CallLog.Calls.NUMBER)
                val iName = cur.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iType = cur.getColumnIndex(CallLog.Calls.TYPE)
                val iDate = cur.getColumnIndex(CallLog.Calls.DATE)
                val iNumType = cur.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
                val iNumLabel = cur.getColumnIndex(CallLog.Calls.CACHED_NUMBER_LABEL)
                val iGeo = cur.getColumnIndex("geocoded_location")
                while (cur.moveToNext() && out.size < limit) {
                    val number = if (iNum >= 0) cur.getString(iNum).orEmpty() else ""
                    var label = ""
                    if (iNumType >= 0 && !cur.isNull(iNumType) && cur.getInt(iNumType) != 0) {
                        val custom = if (iNumLabel >= 0) cur.getString(iNumLabel) else null
                        label = try {
                            ContactsContract.CommonDataKinds.Phone
                                .getTypeLabel(c.resources, cur.getInt(iNumType), custom).toString()
                        } catch (e: Exception) {
                            ""
                        }
                    }
                    if (label.isEmpty() && iGeo >= 0) label = cur.getString(iGeo).orEmpty()
                    out.add(
                        LogEntry(
                            if (iId >= 0) cur.getLong(iId) else 0L,
                            number,
                            if (iName >= 0) cur.getString(iName) else null,
                            if (iType >= 0) cur.getInt(iType) else 0,
                            if (iDate >= 0) cur.getLong(iDate) else 0L,
                            label
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun deleteLog(c: Context, id: Long) {
        if (!has(c, Manifest.permission.WRITE_CALL_LOG)) return
        try {
            c.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID}=?", arrayOf(id.toString()))
        } catch (_: Exception) {
        }
    }

    /** Читает все номера и склеивает людей с одинаковым именем (даже из разных аккаунтов) в одну карточку. */
    private fun readContacts(c: Context): List<Contact> {
        if (!has(c, Manifest.permission.READ_CONTACTS)) return emptyList()
        class G(val name: String) {
            val ids = ArrayList<Long>()
            val nums = ArrayList<PhoneNum>()
            var key: String? = null
            var starred = false
            var photo: String? = null
        }
        val groups = LinkedHashMap<String, G>()
        try {
            val proj = arrayOf(
                P.CONTACT_ID, P.LOOKUP_KEY, P.DISPLAY_NAME, P.NUMBER, P.STARRED, P.PHOTO_URI, P._ID, P.TYPE, P.LABEL
            )
            c.contentResolver.query(
                P.CONTENT_URI, proj, null, null, P.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(2)?.trim() ?: continue
                    if (name.isEmpty()) continue
                    val g = groups.getOrPut(Logic.normalize(name).ifEmpty { name }) { G(name) }
                    val id = cur.getLong(0)
                    if (!g.ids.contains(id)) g.ids.add(id)
                    if (g.key == null) g.key = cur.getString(1)
                    if (cur.getInt(4) == 1) g.starred = true
                    if (g.photo == null) g.photo = cur.getString(5)
                    val number = cur.getString(3).orEmpty()
                    if (number.isBlank()) continue
                    val k = numKey(number)
                    if (g.nums.any { numKey(it.number) == k }) continue
                    val label = try {
                        P.getTypeLabel(c.resources, cur.getInt(7), cur.getString(8)).toString()
                    } catch (e: Exception) {
                        ""
                    }
                    g.nums.add(PhoneNum(cur.getLong(6), number, label))
                }
            }
        } catch (_: Exception) {
        }
        return groups.values.map { g ->
            Contact(
                g.ids.first(), g.key, g.name, g.nums.firstOrNull()?.number.orEmpty(), g.starred, g.photo,
                g.nums.firstOrNull()?.dataId ?: -1L, g.nums.toList(), g.ids.toList()
            )
        }
    }

    fun contacts(c: Context): List<Contact> = readContacts(c)

    fun contact(c: Context, id: Long): Contact? = readContacts(c).firstOrNull { id in it.ids }

    /** Ключ для сопоставления номеров: последние 10 цифр (8 999… и +7 999… дают одно и то же). */
    fun numKey(number: String): String = number.filter { it.isDigit() }.takeLast(10)


    private fun photoOps(ops: ArrayList<ContentProviderOperation>, raw: Long?, backRef: Int?, photo: ByteArray?, remove: Boolean) {
        if (raw != null && (photo != null || remove)) {
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(raw.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    ).build()
            )
        }
        if (photo != null) {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            if (raw != null) b.withValue(ContactsContract.Data.RAW_CONTACT_ID, raw)
            else b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef ?: 0)
            ops.add(
                b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photo)
                    .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                    .build()
            )
        }
    }

    fun insertContact(c: Context, name: String, numbers: List<String>, photo: ByteArray? = null): Boolean {
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) return false
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            for (number in numbers) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, P.CONTENT_ITEM_TYPE)
                        .withValue(P.NUMBER, number)
                        .withValue(P.TYPE, P.TYPE_MOBILE)
                        .build()
                )
            }
            photoOps(ops, null, 0, photo, false)
            c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun firstRawContact(c: Context, contactId: Long): Long? {
        return try {
            c.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(contactId.toString()), null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Сохраняет карточку: имя во всех склеенных контактах, номера — изменить / добавить / удалить.
     * [numbers]: пары (dataId существующего номера или -1 для нового, номер).
     */
    fun saveContact(c: Context, old: Contact, name: String, numbers: List<Pair<Long, String>>, photo: ByteArray? = null, removePhoto: Boolean = false): Boolean {
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) return false
        val raw = firstRawContact(c, old.id) ?: return false
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            if (name != old.name) {
                for (id in old.ids) {
                    val r = firstRawContact(c, id) ?: continue
                    ops.add(
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                                arrayOf(r.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            )
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                            .build()
                    )
                }
            }
            val keep = numbers.filter { it.first >= 0 }.associate { it.first to it.second }
            for (n in old.numbers) {
                val now = keep[n.dataId]
                if (now == null) {
                    ops.add(
                        ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                            .withSelection("${ContactsContract.Data._ID}=?", arrayOf(n.dataId.toString()))
                            .build()
                    )
                } else if (now != n.number) {
                    ops.add(
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection("${ContactsContract.Data._ID}=?", arrayOf(n.dataId.toString()))
                            .withValue(P.NUMBER, now)
                            .build()
                    )
                }
            }
            for ((id, num) in numbers) {
                if (id >= 0) continue
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw)
                        .withValue(ContactsContract.Data.MIMETYPE, P.CONTENT_ITEM_TYPE)
                        .withValue(P.NUMBER, num)
                        .withValue(P.TYPE, P.TYPE_MOBILE)
                        .build()
                )
            }
            for (id in old.ids) {
                if (removePhoto || (photo != null && id != old.id)) photoOps(ops, firstRawContact(c, id), null, null, true)
            }
            photoOps(ops, raw, null, photo, false)
            if (ops.isNotEmpty()) c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Удаляет человека целиком — все склеенные контакты с его именем. */
    fun deleteContact(c: Context, ids: List<Long>): Boolean {
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) return false
        var ok = false
        for (id in ids) {
            try {
                if (c.contentResolver.delete(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString()), null, null) > 0) ok = true
            } catch (_: Exception) {
            }
        }
        return ok
    }

    fun setStarred(c: Context, ids: List<Long>, starred: Boolean): Boolean {
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) return false
        var ok = false
        for (id in ids) {
            try {
                val v = ContentValues()
                v.put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
                if (c.contentResolver.update(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString()), v, null, null) > 0) ok = true
            } catch (_: Exception) {
            }
        }
        return ok
    }

    /** «12:30» сегодня, «Вчера», иначе «12.11». */
    fun when_(ms: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = ms
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            sameYear && dayDiff == 1 -> "Вчера"
            else -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(ms))
        }
    }
}
