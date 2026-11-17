@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Свой экран контакта: создать, изменить, позвонить, написать, в избранное, удалить. */
class ContactEditActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID = "contact_id"
        const val EXTRA_NUMBER = "prefill_number"
        const val EXTRA_NAME = "prefill_name"
    }

    private var contact: Contact? = null
    private lateinit var nameInput: EditText
    private lateinit var avatarView: AvatarView
    private var newPhoto: ByteArray? = null
    private var photoRemoved = false
    private class NumRow(val dataId: Long, val edit: EditText, val view: View)

    private val numRows = ArrayList<NumRow>()
    private lateinit var numBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id >= 0) contact = Repo.contact(this, id)
        build()
        if (!Perms.has(this, Manifest.permission.WRITE_CONTACTS)) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), 1)
        }
    }

    private fun input(hint: String, value: String, phone: Boolean): EditText {
        val a = this
        val e = EditText(a)
        e.hint = hint
        e.setHintTextColor(Theme.sub)
        e.setTextColor(Theme.text)
        e.textSize = 18f
        e.isSingleLine = true
        e.inputType = if (phone) InputType.TYPE_CLASS_PHONE else (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        e.setText(value)
        e.background = Ui.rounded(Theme.card, Ui.dpf(a, 18f))
        e.setPadding(Ui.dp(a, 18), Ui.dp(a, 15), Ui.dp(a, 18), Ui.dp(a, 15))
        return e
    }

    private fun photoSheet() {
        Ui.sheet(this, "Фото контакта", null, listOf(
            Triple(R.drawable.ic_person_add, "Выбрать из галереи") {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.type = "image/*"
                try {
                    startActivityForResult(Intent.createChooser(i, "Фото"), 77)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не нашёл галерею", Toast.LENGTH_SHORT).show()
                }
            },
            Triple(R.drawable.ic_delete, "Убрать фото") {
                newPhoto = null
                photoRemoved = true
                avatarView.bind(nameInput.text.toString(), null)
            }
        ))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 77 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val o = android.graphics.BitmapFactory.Options()
            o.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 720 && o.outHeight / (sample * 2) >= 720) sample *= 2
            val o2 = android.graphics.BitmapFactory.Options()
            o2.inSampleSize = sample
            val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return
            // квадрат по центру, не больше 720 px
            val side = minOf(src.width, src.height)
            val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val out = if (side > 720) android.graphics.Bitmap.createScaledBitmap(sq, 720, 720, true) else sq
            val bos = java.io.ByteArrayOutputStream()
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos)
            newPhoto = bos.toByteArray()
            photoRemoved = false
            val f = java.io.File(cacheDir, "avatar_" + System.currentTimeMillis() + ".jpg")
            f.writeBytes(newPhoto!!)
            avatarView.bind(nameInput.text.toString(), Uri.fromFile(f).toString())
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addNumberRow(dataId: Long, number: String) {
        val a = this
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val e = input("Телефон", number, true)
        row.addView(e, Ui.lp(0, Ui.WRAP, 1f))
        val del = Ui.roundButton(a, 46, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_delete, Theme.RED, 24)
        val nr = NumRow(dataId, e, row)
        del.setOnClickListener {
            // последний номер просто очищаем, остальные убираем
            if (numRows.size <= 1) {
                e.setText("")
            } else {
                numRows.remove(nr)
                numBox.removeView(row)
            }
        }
        row.addView(del, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 4), 0, 0, 0))
        numRows.add(nr)
        numBox.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))
    }

    private fun build() {
        val a = this
        val c = contact
        val scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 32))
        scroll.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.roundButton(a, 46, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_arrow_back, Theme.text, 24)
        back.setOnClickListener { finish() }
        top.addView(back)
        top.addView(
            Ui.light(a, if (c == null) "Новый контакт" else "Контакт", 28f, Theme.text),
            Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 6), 0, 0, 0)
        )
        col.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), -Ui.dp(a, 8), Ui.dp(a, 8), 0, Ui.dp(a, 8)))

        val avatar = AvatarView(a, Ui.dp(a, 110), 40f)
        avatarView = avatar
        avatar.bind(c?.name, c?.photo)
        avatar.isClickable = true
        avatar.setOnClickListener { photoSheet() }
        col.addView(avatar, Ui.margins(Ui.lp(Ui.dp(a, 110), Ui.dp(a, 110)), 0, Ui.dp(a, 8), 0, Ui.dp(a, 6)))
        (avatar.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        val change = Ui.text(a, "Изменить фото", 14f, Theme.accent)
        change.gravity = Gravity.CENTER
        change.setOnClickListener { photoSheet() }
        col.addView(change, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(a, 16)))

        nameInput = input("Имя", c?.name ?: intent.getStringExtra(EXTRA_NAME).orEmpty(), false)
        col.addView(nameInput, Ui.lp(Ui.MATCH, Ui.WRAP))

        numBox = LinearLayout(a)
        numBox.orientation = LinearLayout.VERTICAL
        numRows.clear()
        if (c != null && c.numbers.isNotEmpty()) {
            for (n in c.numbers) addNumberRow(n.dataId, n.number)
        } else {
            addNumberRow(-1L, intent.getStringExtra(EXTRA_NUMBER).orEmpty())
        }
        col.addView(numBox, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(pill("+ Добавить номер", false) { addNumberRow(-1L, "") }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))

        col.addView(pill("Сохранить", true) { save() }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 20), 0, 0))

        if (c != null) {
            col.addView(pill("Позвонить", false) { call(c.numbers.firstOrNull()?.number ?: c.number) }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
            col.addView(pill("Написать SMS", false) {
                Perms.open(a, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.numbers.firstOrNull()?.number ?: c.number))))
            }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
            col.addView(pill(if (c.starred) "Убрать из избранного" else "В избранное ★", false) {
                if (Repo.setStarred(a, c.ids, !c.starred)) {
                    finish()
                } else {
                    Toast.makeText(a, "Нужно разрешение на изменение контактов", Toast.LENGTH_LONG).show()
                }
            }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
            val del = pill("Удалить контакт", false) { confirmDelete(c) }
            (del as TextView).setTextColor(Theme.RED)
            col.addView(del, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
        }
        setContentView(scroll)
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit): View {
        val a = this
        val b = Ui.text(a, label, 16f, if (primary) Color.WHITE else Theme.text, primary)
        b.gravity = Gravity.CENTER
        b.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14))
        val fill = if (primary) Ui.accentFill(Ui.dpf(a, 24f)) else Ui.rounded(Theme.card, Ui.dpf(a, 24f))
        b.background = Ui.ripple(fill, Ui.dpf(a, 24f))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun call(number: String) {
        if (!Perms.has(this, Manifest.permission.CALL_PHONE)) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 2)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось позвонить", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        val nums = numRows.map { it.dataId to it.edit.text.toString().trim() }.filter { it.second.isNotEmpty() }
        if (name.isEmpty() || nums.isEmpty()) {
            Toast.makeText(this, "Введите имя и хотя бы один номер", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Perms.has(this, Manifest.permission.WRITE_CONTACTS)) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), 1)
            Toast.makeText(this, "Разрешите изменение контактов и нажмите «Сохранить» ещё раз", Toast.LENGTH_LONG).show()
            return
        }
        val c = contact
        val ok = if (c == null) Repo.insertContact(this, name, nums.map { it.second }, newPhoto) else Repo.saveContact(this, c, name, nums, newPhoto, photoRemoved)
        if (ok) {
            Avatars.clear()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Не удалось сохранить контакт", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(c: Contact) {
        Ui.sheet(
            this, "Удалить «${c.name}»?", "Контакт исчезнет с телефона",
            listOf(Triple(R.drawable.ic_delete, "Да, удалить") {
                if (Repo.deleteContact(this, c.ids)) {
                    finish()
                } else {
                    Toast.makeText(this, "Не удалось удалить", Toast.LENGTH_LONG).show()
                }
            })
        )
    }
}
