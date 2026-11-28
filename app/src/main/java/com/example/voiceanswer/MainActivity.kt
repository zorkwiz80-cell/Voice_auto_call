@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Главный экран: «Недавние», «Контакты» и набор номера — как в обычном телефоне. */
class MainActivity : BaseActivity() {

    companion object {
        const val REQ_PERMS = 10
        const val REQ_ROLE = 11
        const val REQ_CALL = 12
        const val REQ_MIC = 13
        const val REQ_RINGTONE = 14
        const val REQ_AUDIO = 15
    }

    private class Row(val header: String?, val contact: Contact?)

    private inner class Banner {
        val box = LinearLayout(this@MainActivity)
        val title: TextView
        val body: TextView
        val button: TextView

        init {
            val a = this@MainActivity
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16))
            box.background = Ui.rounded(Theme.card, Ui.dpf(a, 22f))
            title = Ui.text(a, "", 16f, Theme.text, true)
            body = Ui.text(a, "", 14f, Theme.sub)
            button = Ui.text(a, "", 15f, Color.WHITE, true)
            button.gravity = Gravity.CENTER
            button.setPadding(Ui.dp(a, 20), Ui.dp(a, 11), Ui.dp(a, 20), Ui.dp(a, 11))
            button.background = Ui.ripple(Ui.accentFill(Ui.dpf(a, 22f)), Ui.dpf(a, 22f))
            button.isClickable = true
            box.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP))
            box.addView(body, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 4), 0, Ui.dp(a, 12)))
            box.addView(button, Ui.lp(Ui.WRAP, Ui.WRAP))
        }

        fun update() {
            val a = this@MainActivity
            val missing = Perms.missing(a)
            when {
                missing.isNotEmpty() -> {
                    box.visibility = View.VISIBLE
                    title.text = "Нужны разрешения"
                    body.text = "Журнал звонков, контакты, микрофон и телефон — без них приложение не сможет работать."
                    button.text = "Разрешить"
                    button.setOnClickListener { requestPermissions(missing.toTypedArray(), REQ_PERMS) }
                }
                !Perms.isDefaultDialer(a) -> {
                    box.visibility = View.VISIBLE
                    title.text = "Сделайте «Голосовой ответ» основным телефоном"
                    body.text = "Тогда этот экран звонка и голосовое управление заработают при каждом вызове."
                    button.text = "Сделать основным"
                    button.setOnClickListener { Perms.requestDefaultDialer(a, REQ_ROLE) }
                }
                else -> box.visibility = View.GONE
            }
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var recentsPage: LinearLayout
    private lateinit var contactsPage: LinearLayout
    private lateinit var recentsEmpty: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabMissed: TextView
    private lateinit var searchBox: EditText
    private lateinit var contactsSub: TextView
    private lateinit var fabDial: View
    private lateinit var fabAdd: View
    private lateinit var navIconRecents: FrameLayout
    private lateinit var navIconContacts: FrameLayout
    private lateinit var navTextRecents: TextView
    private lateinit var navTextContacts: TextView
    private lateinit var dialPanel: LinearLayout
    private lateinit var dialField: EditText
    private var dialSyncing = false
    private var maxSuggest = 3
    private lateinit var dialHint: TextView
    private lateinit var contactsEmpty: TextView

    private val banners = ArrayList<Banner>()
    private val dialed = StringBuilder()
    private var page = 0
    private var dialOpen = false
    private lateinit var suggestBox: LinearLayout
    private var log: List<LogEntry> = emptyList()
    private var contacts: List<Contact> = emptyList()
    private var missedOnly = false
    private var logQuery = ""
    private var contactQuery = ""
    private var pendingCall: String? = null
    private var photoByNumber: Map<String, String> = emptyMap()

    private val logAdapter = LogAdapter()
    private val contactAdapter = ContactAdapter()

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val missing = Perms.missing(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        Calls.warmUp(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> {
                val d = i.data
                if (d != null && d.scheme == "tel") openDial(Uri.decode(d.schemeSpecificPart ?: "")) else if (i.action == Intent.ACTION_DIAL) openDial("")
            }
            "android.intent.action.CALL_BUTTON" -> openDial("")
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        for (b in banners) b.update()
        reload()
        if (Calls.calls.isEmpty()) VoiceListener.restoreSound(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (b in banners) b.update()
        reload()
        if (requestCode == REQ_MIC && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceCall()
        }
        if (requestCode == REQ_AUDIO) openRingtonePicker()
        if (requestCode == REQ_CALL) {
            val n = pendingCall
            pendingCall = null
            if (n != null && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) placeCall(n)
        }
        Calls.warmUp(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RINGTONE && resultCode == RESULT_OK && data != null) onRingtonePicked(data)
        for (b in banners) b.update()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            dialOpen -> closeDial()
            searchBox.visibility == View.VISIBLE -> toggleSearch()
            page != 0 -> showPage(0)
            else -> super.onBackPressed()
        }
    }

    private fun reload() {
        io.execute {
            val c = Repo.contacts(this)
            val photos = HashMap<String, String>()
            val names = HashMap<String, String>()
            for (ct in c) {
                for (n in ct.numbers) {
                    val k = Repo.numKey(n.number)
                    if (k.isEmpty()) continue
                    names[k] = ct.name
                    if (ct.photo != null) photos[k] = ct.photo
                }
            }
            // имя из контактов важнее старой записи в журнале
            val l = Repo.callLog(this).map { e ->
                val nm = names[Repo.numKey(e.number)]
                if (nm != null && nm != e.name) LogEntry(e.id, e.number, nm, e.type, e.date, e.label, e.duration) else e
            }
            main.post {
                if (isDestroyed) return@post
                photoByNumber = photos
                log = l
                contacts = c
                applyLog()
                applyContacts()
            }
        }
    }

    // ------------------------------------------------------------------ построение интерфейса

    private fun iconButton(res: Int, onClick: () -> Unit): FrameLayout {
        val b = Ui.roundButton(this, 46, ColorDrawable(Color.TRANSPARENT), res, Theme.text, 24)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val a = this
        val root = FrameLayout(a)
        if (!Theme.themed) root.setBackgroundColor(Theme.bg)

        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val pages = FrameLayout(a)
        recentsPage = buildRecents()
        contactsPage = buildContacts()
        pages.addView(recentsPage, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        pages.addView(contactsPage, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        col.addView(pages, Ui.lp(Ui.MATCH, 0, 1f))
        col.addView(buildNav(), Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        val fabLp = { FrameLayout.LayoutParams(Ui.dp(a, 62), Ui.dp(a, 62), Gravity.BOTTOM or Gravity.END).also {
            it.setMargins(0, 0, Ui.dp(a, 20), Ui.dp(a, 84))
        } }
        val dial = Ui.roundButton(a, 62, Ui.oval(Theme.card2), R.drawable.ic_dialpad, Theme.accent, 26)
        dial.setOnClickListener { openDial("") }
        val dialIcon = dial.getChildAt(0) as ImageView
        onAccent { c -> dialIcon.setColorFilter(c) }
        fabDial = dial
        root.addView(dial, fabLp())

        val add = Ui.roundButton(a, 62, Ui.oval(Theme.card2), R.drawable.ic_person_add, Theme.accent, 26)
        add.setOnClickListener {
            startActivity(Intent(a, ContactEditActivity::class.java))
        }
        val addIcon = add.getChildAt(0) as ImageView
        onAccent { c -> addIcon.setColorFilter(c) }
        fabAdd = add
        root.addView(add, fabLp())

        dialPanel = buildDial()
        dialPanel.visibility = View.GONE
        root.addView(dialPanel, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        setContentView(root)
        showPage(0)
    }

    private fun header(title: String, withSearch: Boolean): LinearLayout {
        val a = this
        val h = LinearLayout(a)
        h.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(a)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        if (withSearch) top.addView(iconButton(R.drawable.ic_search) { toggleSearch() })
        top.addView(iconButton(R.drawable.ic_mic) { startVoiceCall() })
        top.addView(iconButton(R.drawable.ic_settings) { startActivity(Intent(a, SettingsActivity::class.java)) })
        h.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 8), Ui.dp(a, 10), 0))
        val t = Ui.light(a, title, 38f, Theme.text)
        h.addView(t, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 24), Ui.dp(a, 4), 0, 0))
        return h
    }

    private fun searchField(hint: String, onChange: (String) -> Unit): EditText {
        val a = this
        val e = EditText(a)
        e.hint = hint
        e.setHintTextColor(Theme.sub)
        e.setTextColor(Theme.text)
        e.textSize = 16f
        e.isSingleLine = true
        e.background = Ui.rounded(Theme.card, Ui.dpf(a, 18f))
        e.setPadding(Ui.dp(a, 18), Ui.dp(a, 13), Ui.dp(a, 18), Ui.dp(a, 13))
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        return e
    }

    private fun footer(l: ListView) {
        val f = View(this)
        f.layoutParams = android.widget.AbsListView.LayoutParams(Ui.MATCH, Ui.dp(this, 150))
        l.addFooterView(f, null, false)
    }

    private fun buildRecents(): LinearLayout {
        val a = this
        val page = LinearLayout(a)
        page.orientation = LinearLayout.VERTICAL
        page.addView(header("Недавние", true), Ui.lp(Ui.MATCH, Ui.WRAP))

        searchBox = searchField("Поиск по журналу") {
            logQuery = it
            applyLog()
        }
        searchBox.visibility = View.GONE
        page.addView(searchBox, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 4)))

        val tabs = LinearLayout(a)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabAll = Ui.text(a, "Все", 18f, Theme.text, true)
        tabMissed = Ui.text(a, "Пропущенные", 18f, Theme.sub, true)
        for ((i, t) in listOf(tabAll, tabMissed).withIndex()) {
            t.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10))
            t.isClickable = true
            t.setOnClickListener {
                missedOnly = i == 1
                styleTabs()
                applyLog()
            }
        }
        tabs.addView(tabAll, Ui.lp(0, Ui.WRAP, 1f))
        tabs.addView(tabMissed, Ui.lp(0, Ui.WRAP, 1f))
        tabAll.gravity = Gravity.CENTER
        tabMissed.gravity = Gravity.CENTER
        page.addView(tabs, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 14), Ui.dp(a, 20), Ui.dp(a, 4)))
        styleTabs()

        val banner = Banner()
        banners.add(banner)
        page.addView(banner.box, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 20), Ui.dp(a, 4)))

        val holder = FrameLayout(a)
        val list = ListView(a)
        footer(list)
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.isVerticalScrollBarEnabled = false
        list.adapter = logAdapter
        holder.addView(list, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        recentsEmpty = Ui.text(a, "", 16f, Theme.sub)
        recentsEmpty.gravity = Gravity.CENTER
        recentsEmpty.setPadding(Ui.dp(a, 32), 0, Ui.dp(a, 32), Ui.dp(a, 80))
        holder.addView(recentsEmpty, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        page.addView(holder, Ui.lp(Ui.MATCH, 0, 1f))
        return page
    }

    private fun styleTabs() {
        tabAll.setTextColor(if (!missedOnly) Theme.accent else Theme.sub)
        tabMissed.setTextColor(if (missedOnly) Theme.accent else Theme.sub)
        // Для радужной темы цвет подпишется заново на каждом кадре
    }

    private fun buildContacts(): LinearLayout {
        val a = this
        val page = LinearLayout(a)
        page.orientation = LinearLayout.VERTICAL
        page.addView(header("Контакты", false), Ui.lp(Ui.MATCH, Ui.WRAP))
        contactsSub = Ui.text(a, "", 15f, Theme.sub)
        page.addView(contactsSub, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 25), 0, 0, 0))

        val search = searchField("Поиск") {
            contactQuery = it
            applyContacts()
        }
        page.addView(search, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 14), Ui.dp(a, 20), Ui.dp(a, 6)))

        val banner = Banner()
        banners.add(banner)
        page.addView(banner.box, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 4)))

        val holder = FrameLayout(a)
        val list = ListView(a)
        footer(list)
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.isVerticalScrollBarEnabled = false
        list.adapter = contactAdapter
        holder.addView(list, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        contactsEmpty = Ui.text(a, "", 16f, Theme.sub)
        contactsEmpty.gravity = Gravity.CENTER
        contactsEmpty.setPadding(Ui.dp(a, 32), 0, Ui.dp(a, 32), Ui.dp(a, 80))
        holder.addView(contactsEmpty, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        page.addView(holder, Ui.lp(Ui.MATCH, 0, 1f))
        return page
    }

    private fun navItem(res: Int, label: String, onClick: () -> Unit, out: (FrameLayout, TextView) -> Unit): View {
        val a = this
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8))
        val circle = FrameLayout(a)
        val icon = Ui.icon(a, res, Color.WHITE)
        circle.addView(icon, FrameLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24), Gravity.CENTER))
        box.addView(circle, Ui.lp(Ui.dp(a, 52), Ui.dp(a, 34)))
        val t = Ui.text(a, label, 12f, Theme.sub)
        box.addView(t, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 2), 0, 0))
        box.isClickable = true
        box.setOnClickListener { onClick() }
        out(circle, t)
        return box
    }

    private fun buildNav(): View {
        val a = this
        val wrap = LinearLayout(a)
        wrap.orientation = LinearLayout.VERTICAL
        val line = View(a)
        line.setBackgroundColor(Theme.line)
        wrap.addView(line, Ui.lp(Ui.MATCH, 1))
        val nav = LinearLayout(a)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Theme.panel)
        nav.addView(navItem(R.drawable.ic_history, "Недавние", { showPage(0) }) { c, t ->
            navIconRecents = c
            navTextRecents = t
        }, Ui.lp(0, Ui.WRAP, 1f))
        nav.addView(navItem(R.drawable.ic_person, "Контакты", { showPage(1) }) { c, t ->
            navIconContacts = c
            navTextContacts = t
        }, Ui.lp(0, Ui.WRAP, 1f))
        wrap.addView(nav, Ui.lp(Ui.MATCH, Ui.WRAP))
        onAccent { styleNav() }
        return wrap
    }

    private fun styleNav() {
        val a = this
        fun apply(circle: FrameLayout, text: TextView, selected: Boolean) {
            val icon = circle.getChildAt(0) as ImageView
            if (selected) {
                circle.background = Ui.accentFill(Ui.dpf(a, 17f))
                icon.setColorFilter(Color.WHITE)
                text.setTextColor(Theme.accent)
            } else {
                circle.background = null
                icon.setColorFilter(Theme.sub)
                text.setTextColor(Theme.sub)
            }
        }
        if (!::navIconRecents.isInitialized || !::navIconContacts.isInitialized) return
        apply(navIconRecents, navTextRecents, page == 0)
        apply(navIconContacts, navTextContacts, page == 1)
        if (::tabAll.isInitialized) styleTabs()
    }

    private fun showPage(i: Int) {
        page = i
        recentsPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        contactsPage.visibility = if (i == 1) View.VISIBLE else View.GONE
        fabDial.visibility = if (i == 0 && !dialOpen) View.VISIBLE else View.GONE
        fabAdd.visibility = if (i == 1 && !dialOpen) View.VISIBLE else View.GONE
        styleNav()
    }

    private fun toggleSearch() {
        val show = searchBox.visibility != View.VISIBLE
        searchBox.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            searchBox.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchBox, 0)
        } else {
            searchBox.setText("")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        }
    }

    // ------------------------------------------------------------------ данные → списки

    private fun applyLog() {
        val q = logQuery.trim().lowercase()
        val list = log.filter {
            (!missedOnly || it.type == CallLog.Calls.MISSED_TYPE) &&
                (q.isEmpty() || it.title.lowercase().contains(q) || it.number.contains(q))
        }
        logAdapter.items = list
        logAdapter.notifyDataSetChanged()
        recentsEmpty.text = when {
            !Perms.has(this, Manifest.permission.READ_CALL_LOG) -> "Нет доступа к журналу звонков.\nНажмите «Разрешить» выше."
            missedOnly -> "Пропущенных звонков нет"
            q.isNotEmpty() -> "Ничего не найдено"
            else -> "Звонков пока нет"
        }
        recentsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun applyContacts() {
        val q = contactQuery.trim().lowercase()
        val rows = ArrayList<Row>()
        if (q.isNotEmpty()) {
            val qd = q.filter { it.isDigit() }
            for (c in contacts) {
                if (c.name.lowercase().contains(q) || (qd.isNotEmpty() && c.numbers.any { n -> n.number.filter { it.isDigit() }.contains(qd) })) {
                    rows.add(Row(null, c))
                }
            }
        } else {
            val fav = contacts.filter { it.starred }
            if (fav.isNotEmpty()) {
                rows.add(Row("★", null))
                for (c in fav) rows.add(Row(null, c))
            }
            var last = ""
            for (c in contacts) {
                val ch = c.name.firstOrNull()
                val letter = if (ch != null && ch.isLetter()) ch.uppercaseChar().toString() else "#"
                if (letter != last) {
                    rows.add(Row(letter, null))
                    last = letter
                }
                rows.add(Row(null, c))
            }
        }
        contactAdapter.items = rows
        contactAdapter.notifyDataSetChanged()
        val n = contacts.size
        contactsSub.text = "$n " + Logic.plural(n, "контакт", "контакта", "контактов")
        contactsEmpty.text = if (!Perms.has(this, Manifest.permission.READ_CONTACTS)) "Нет доступа к контактам.\nНажмите «Разрешить» выше." else "Ничего не найдено"
        contactsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ адаптеры

    private inner class LogAdapter : BaseAdapter() {
        var items: List<LogEntry> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? LogHolder) ?: LogHolder().also { it.root.tag = it }
            h.bind(items[position])
            return h.root
        }
    }

    private inner class LogHolder {
        val a = this@MainActivity
        val root = LinearLayout(a)
        val avatar = AvatarView(a, Ui.dp(a, 46), 17f)
        val typeIcon = ImageView(a)
        val title = Ui.text(a, "", 18f, Theme.text)
        val sub = Ui.text(a, "", 14f, Theme.sub)
        val time = Ui.text(a, "", 14f, Theme.sub)
        val info = FrameLayout(a)

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10))
            root.background = Ui.ripple(ColorDrawable(Color.TRANSPARENT), 0f)
            root.layoutParams = android.widget.AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP)
            root.addView(avatar, Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)))
            val col = LinearLayout(a)
            col.orientation = LinearLayout.VERTICAL
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            sub.maxLines = 1
            sub.ellipsize = TextUtils.TruncateAt.END
            col.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP))
            val line2 = LinearLayout(a)
            line2.orientation = LinearLayout.HORIZONTAL
            line2.gravity = Gravity.CENTER_VERTICAL
            line2.addView(typeIcon, Ui.margins(Ui.lp(Ui.dp(a, 16), Ui.dp(a, 16)), 0, 0, Ui.dp(a, 6), 0))
            line2.addView(sub, Ui.lp(Ui.WRAP, Ui.WRAP))
            col.addView(line2, Ui.lp(Ui.MATCH, Ui.WRAP))
            root.addView(col, Ui.margins(Ui.lp(0, Ui.WRAP, 1f), Ui.dp(a, 14), 0, Ui.dp(a, 8), 0))
            root.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP))
            val i = Ui.icon(a, R.drawable.ic_info, Theme.sub)
            info.addView(i, FrameLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26), Gravity.CENTER))
            info.background = Ui.ripple(ColorDrawable(Color.TRANSPARENT), 0f, true)
            info.isClickable = true
            root.addView(info, Ui.margins(Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)), Ui.dp(a, 6), 0, 0, 0))
        }

        fun bind(e: LogEntry) {
            typeIcon.setImageResource(
                when {
                    e.missed -> R.drawable.ic_call_missed
                    e.outgoing -> R.drawable.ic_call_made
                    else -> R.drawable.ic_call_received
                }
            )
            typeIcon.setColorFilter(if (e.missed) Theme.RED else Theme.sub)
            avatar.bind(e.name, photoByNumber[Repo.numKey(e.number)])
            title.text = e.title
            title.setTextColor(if (e.missed) Theme.RED else Theme.text)
            val kind = when {
                e.missed -> "Пропущенный"
                e.outgoing -> "Исходящий"
                else -> "Входящий"
            }
            val dur = if (e.duration > 0L) " · " + Logic.formatDuration(e.duration) else ""
            sub.text = (if (e.label.isEmpty()) kind else kind + " · " + e.label) + dur
            time.text = Repo.when_(e.date)
            root.setOnClickListener { placeCall(e.number) }
            info.setOnClickListener { logSheet(e) }
        }
    }

    private inner class ContactAdapter : BaseAdapter() {
        var items: List<Row> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position].header != null) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = items[position]
            if (row.header != null) {
                val t = (convertView as? TextView) ?: Ui.text(this@MainActivity, "", 16f, Theme.sub, true).also {
                    it.setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 4))
                    it.layoutParams = android.widget.AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP)
                }
                t.text = row.header
                return t
            }
            val h = (convertView?.tag as? ContactHolder) ?: ContactHolder().also { it.root.tag = it }
            h.bind(row.contact!!)
            return h.root
        }
    }

    private inner class ContactHolder {
        val a = this@MainActivity
        val root = LinearLayout(a)
        val avatar = AvatarView(a, Ui.dp(a, 46), 17f)
        val name = Ui.text(a, "", 18f, Theme.text)

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8))
            root.background = Ui.ripple(ColorDrawable(Color.TRANSPARENT), 0f)
            root.layoutParams = android.widget.AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP)
            root.addView(avatar, Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)))
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            root.addView(name, Ui.margins(Ui.lp(0, Ui.WRAP, 1f), Ui.dp(a, 16), 0, 0, 0))
        }

        fun bind(c: Contact) {
            avatar.bind(c.name, c.photo)
            name.text = c.name
            root.setOnClickListener { contactSheet(c) }
        }
    }

    // ------------------------------------------------------------------ шторки с действиями

    private fun smsIntent(number: String) = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))

    private fun copy(number: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Номер", number))
        Toast.makeText(this, "Номер скопирован", Toast.LENGTH_SHORT).show()
    }

    /** Все номера человека и его 5 последних звонков (по любому из номеров) с датой, временем и длительностью. */
    private var pendingRingKeys: Set<String> = emptySet()

    /** Открывает системный выбор мелодии для этого абонента. */
    private var pendingRingCurrent: String? = null

    private fun audioPerm(): String =
        if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun pickRingtone(keys: Set<String>, current: String?) {
        pendingRingKeys = keys
        pendingRingCurrent = current
        // доступ к музыке нужен, чтобы приложение могло проиграть выбранную мелодию при звонке
        if (!Perms.has(this, audioPerm())) {
            requestPermissions(arrayOf(audioPerm()), REQ_AUDIO)
            return
        }
        openRingtonePicker()
    }

    private fun openRingtonePicker() {
        val current = pendingRingCurrent
        try {
            val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_RINGTONE_URI)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Рингтон абонента")
            val existing: Uri? = when (current) {
                null -> Settings.System.DEFAULT_RINGTONE_URI
                "silent" -> null
                else -> Uri.parse(current)
            }
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            startActivityForResult(i, REQ_RINGTONE)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть выбор рингтона", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyRingtone(src: Uri): java.io.File? = try {
        val dir = java.io.File(filesDir, "ringtones")
        dir.mkdirs()
        val name = "r" + (pendingRingKeys.firstOrNull() ?: "x") + "_" + System.currentTimeMillis()
        val dst = java.io.File(dir, name)
        val ok = contentResolver.openInputStream(src)?.use { ins ->
            dst.outputStream().use { out -> ins.copyTo(out) }
            true
        } ?: false
        if (ok && dst.length() > 0) {
            dir.listFiles()?.filter { it.name.startsWith("r" + (pendingRingKeys.firstOrNull() ?: "x") + "_") && it != dst }?.forEach { it.delete() }
            dst
        } else {
            dst.delete()
            null
        }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun onRingtonePicked(data: Intent) {
        val picked: Uri? = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val value: String? = when {
            picked == null -> "silent"
            picked == Settings.System.DEFAULT_RINGTONE_URI -> null
            else -> picked.toString()
        }
        var stored = value
        var copied = true
        if (picked != null && value != null) {
            // копируем мелодию в память приложения: так она точно проиграется при звонке
            val f = copyRingtone(picked)
            if (f != null) stored = Uri.fromFile(f).toString() else copied = false
        }
        Prefs.setRingFor(this, pendingRingKeys, stored)
        Toast.makeText(
            this,
            when {
                value == null -> "Будет звучать стандартный рингтон"
                !copied -> "Рингтон сохранён, но файл может не открыться — тогда зазвучит стандартный"
                else -> "Рингтон абонента сохранён"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun personInfo(box: LinearLayout, nums: List<PhoneNum>, close: () -> Unit) {
        val a = this
        val keys = nums.map { Repo.numKey(it.number) }.filter { it.isNotEmpty() }.toSet()
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        fun header(t: String) {
            box.addView(Ui.text(a, t, 12f, Theme.sub, true), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 16), 0, Ui.dp(a, 4)))
        }

        header(if (nums.size > 1) "НОМЕРА (${nums.size})" else "НОМЕР")
        for (n in nums) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8))
            row.background = Ui.ripple(ColorDrawable(Color.TRANSPARENT), Ui.dpf(a, 12f))
            row.addView(Ui.text(a, Calls.formatNumber(n.number), 17f, Theme.text), Ui.lp(0, Ui.WRAP, 1f))
            if (n.label.isNotBlank()) row.addView(Ui.text(a, n.label, 14f, Theme.sub), Ui.lp(Ui.WRAP, Ui.WRAP))
            row.setOnClickListener {
                close()
                placeCall(n.number)
            }
            box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        }

        // Личный рингтон абонента
        header("РИНГТОН АБОНЕНТА")
        val current = nums.firstNotNullOfOrNull { Ringer.customFor(a, it.number) }
        val ringRow = LinearLayout(a)
        ringRow.orientation = LinearLayout.HORIZONTAL
        ringRow.gravity = Gravity.CENTER_VERTICAL
        ringRow.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8))
        ringRow.background = Ui.ripple(ColorDrawable(Color.TRANSPARENT), Ui.dpf(a, 12f))
        ringRow.addView(Ui.text(a, Ringer.titleOf(a, current), 17f, Theme.text), Ui.lp(0, Ui.WRAP, 1f))
        ringRow.addView(Ui.text(a, "Изменить", 14f, Theme.accent), Ui.lp(Ui.WRAP, Ui.WRAP))
        ringRow.setOnClickListener {
            close()
            pickRingtone(keys, current)
        }
        box.addView(ringRow, Ui.lp(Ui.MATCH, Ui.WRAP))

        val recent = log.filter { Repo.numKey(it.number) in keys }.take(5)
        header("ПОСЛЕДНИЕ ЗВОНКИ")
        if (recent.isEmpty()) {
            box.addView(Ui.text(a, "Звонков в журнале нет", 15f, Theme.sub), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 6), 0, 0, Ui.dp(a, 4)))
        }
        for (r in recent) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(Ui.dp(a, 6), Ui.dp(a, 7), Ui.dp(a, 6), Ui.dp(a, 7))
            val icon = Ui.icon(
                a,
                when {
                    r.missed -> R.drawable.ic_call_missed
                    r.outgoing -> R.drawable.ic_call_made
                    else -> R.drawable.ic_call_received
                },
                if (r.missed) Theme.RED else Theme.sub
            )
            row.addView(icon, Ui.lp(Ui.dp(a, 20), Ui.dp(a, 20)))
            val kind = when {
                r.missed -> "Пропущенный"
                r.outgoing -> "Исходящий"
                else -> "Входящий"
            }
            var left = kind
            if (r.duration > 0L) left += " · " + Logic.formatDuration(r.duration)
            if (keys.size > 1) left += " · " + Calls.formatNumber(r.number)
            row.addView(Ui.text(a, left, 15f, if (r.missed) Theme.RED else Theme.text), Ui.margins(Ui.lp(0, Ui.WRAP, 1f), Ui.dp(a, 12), 0, Ui.dp(a, 8), 0))
            row.addView(Ui.text(a, fmt.format(Date(r.date)), 13f, Theme.sub), Ui.lp(Ui.WRAP, Ui.WRAP))
            box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        }

        val sep = View(a)
        sep.setBackgroundColor(Theme.line)
        box.addView(sep, Ui.margins(Ui.lp(Ui.MATCH, 1), 0, Ui.dp(a, 10), 0, Ui.dp(a, 4)))
    }

    private fun logSheet(e: LogEntry) {
        if (e.number.isBlank()) return
        val actions = ArrayList<Triple<Int, String, () -> Unit>>()
        actions.add(Triple(R.drawable.ic_call, "Позвонить") { placeCall(e.number) })
        actions.add(Triple(R.drawable.ic_message, "Написать SMS") { Perms.open(this, smsIntent(e.number)) })
        actions.add(Triple(R.drawable.ic_content_copy, "Скопировать номер") { copy(e.number) })
        if (e.name.isNullOrBlank()) {
            actions.add(Triple(R.drawable.ic_person_add, "Добавить в контакты") {
                val i = Intent(this, ContactEditActivity::class.java)
                i.putExtra(ContactEditActivity.EXTRA_NUMBER, e.number)
                startActivity(i)
            })
        }
        actions.add(Triple(R.drawable.ic_delete, "Удалить из журнала") {
            io.execute {
                Repo.deleteLog(this, e.id)
                reload()
            }
        })
        val durText = if (e.duration > 0L) " · разговор " + Logic.formatDuration(e.duration) else ""
        val ct = contacts.firstOrNull { c -> c.numbers.any { Repo.numKey(it.number) == Repo.numKey(e.number) } }
        val nums = if (ct != null && ct.numbers.isNotEmpty()) ct.numbers else listOf(PhoneNum(-1L, e.number, ""))
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        Ui.sheet(this, e.title, "Звонок: " + fmt.format(Date(e.date)) + durText, actions) { box, close ->
            personInfo(box, nums, close)
        }
    }

    private fun contactSheet(c: Contact) {
        val actions = ArrayList<Triple<Int, String, () -> Unit>>()
        val nums = if (c.numbers.isEmpty()) listOf(PhoneNum(-1L, c.number, "")) else c.numbers
        for (n in nums) {
            val lab = if (n.label.isNotBlank()) n.label + " · " else ""
            actions.add(Triple(R.drawable.ic_call, "Позвонить · " + lab + Calls.formatNumber(n.number)) { placeCall(n.number) })
        }
        val first = nums.first().number
        actions.add(Triple(R.drawable.ic_message, "Написать SMS") { smsOrPick(nums) })
        actions.add(Triple(R.drawable.ic_content_copy, "Скопировать номер") { copy(first) })
        actions.add(Triple(R.drawable.ic_person, "Изменить контакт") {
            val i = Intent(this, ContactEditActivity::class.java)
            i.putExtra(ContactEditActivity.EXTRA_ID, c.id)
            startActivity(i)
        })
        actions.add(Triple(R.drawable.ic_delete, "Удалить контакт") { confirmDeleteContact(c) })
        Ui.sheet(this, c.name, null, actions) { box, close ->
            personInfo(box, nums, close)
        }
    }

    private fun smsOrPick(nums: List<PhoneNum>) {
        if (nums.size == 1) {
            Perms.open(this, smsIntent(nums[0].number))
            return
        }
        Ui.sheet(this, "Написать SMS", null, nums.map { n ->
            Triple<Int, String, () -> Unit>(R.drawable.ic_message, Calls.formatNumber(n.number)) { Perms.open(this, smsIntent(n.number)) }
        })
    }

    private fun confirmDeleteContact(c: Contact) {
        Ui.sheet(
            this, "Удалить «${c.name}»?", "Исчезнут все номера этого человека (${maxOf(c.numbers.size, 1)})",
            listOf(Triple(R.drawable.ic_delete, "Да, удалить") {
                if (Repo.deleteContact(this, c.ids)) {
                    reload()
                } else {
                    Toast.makeText(this, "Не удалось удалить — нужно разрешение на изменение контактов", Toast.LENGTH_LONG).show()
                }
            })
        )
    }

    // ------------------------------------------------------------------ голосовой звонок: «Позвони Маме»

    private var voiceDial: VoiceDial? = null
    private var voiceDialog: android.app.Dialog? = null

    private fun stopVoiceCall() {
        voiceDial?.stop()
        voiceDial = null
        try {
            voiceDialog?.dismiss()
        } catch (_: Exception) {
        }
        voiceDialog = null
    }

    override fun onPause() {
        stopVoiceCall()
        super.onPause()
    }

    private fun startVoiceCall() {
        val a = this
        if (!Perms.has(a, Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!Perms.has(a, Manifest.permission.READ_CONTACTS)) {
            Toast.makeText(a, "Нужен доступ к контактам", Toast.LENGTH_LONG).show()
            return
        }
        stopVoiceCall()
        val d = android.app.Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(Ui.dp(a, 24), Ui.dp(a, 24), Ui.dp(a, 24), Ui.dp(a, 24))
        box.background = Ui.rounded(Theme.card, Ui.dpf(a, 26f))
        val title = Ui.text(a, "Слушаю…", 22f, Theme.text, true)
        box.addView(title)
        val hint = Ui.text(a, "Скажите: «Позвони Маме» или имя из телефонной книги", 15f, Theme.sub)
        hint.gravity = Gravity.CENTER
        box.addView(hint, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 6), 0, 0))
        val heard = Ui.text(a, "", 18f, Theme.text)
        heard.gravity = Gravity.CENTER
        box.addView(heard, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 16), 0, Ui.dp(a, 16)))
        val cancel = Ui.text(a, "Отмена", 15f, Theme.text)
        cancel.gravity = Gravity.CENTER
        cancel.setPadding(Ui.dp(a, 28), Ui.dp(a, 11), Ui.dp(a, 28), Ui.dp(a, 11))
        cancel.background = Ui.ripple(Ui.rounded(Theme.card2, Ui.dpf(a, 22f)), Ui.dpf(a, 22f))
        cancel.isClickable = true
        cancel.setOnClickListener { stopVoiceCall() }
        box.addView(cancel)
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(resources.displayMetrics.widthPixels - Ui.dp(a, 32), Ui.WRAP)
        d.setOnDismissListener {
            voiceDial?.stop()
            voiceDial = null
        }
        voiceDialog = d
        d.show()

        val vd = VoiceDial(
            a,
            onPartial = { heard.text = it },
            onFinal = { list ->
                heard.text = list[0]
                resolveVoiceCall(list, d)
            },
            onError = { msg ->
                title.text = "Не получилось"
                heard.text = msg
            }
        )
        voiceDial = vd
        vd.start()
    }

    private fun resolveVoiceCall(phrases: List<String>, d: android.app.Dialog) {
        val names = contacts.map { it.name }
        var query = ""
        var found: List<Int> = emptyList()
        for (p in phrases) {
            val q = Logic.parseCall(p) ?: Logic.normalize(p)
            if (q.isEmpty()) continue
            if (query.isEmpty()) query = q
            val f = Logic.findNames(q, names)
            if (f.isNotEmpty()) {
                found = f
                query = q
                break
            }
        }
        d.dismiss()
        when {
            found.isEmpty() -> Toast.makeText(this, "Не нашёл в контактах: «$query»", Toast.LENGTH_LONG).show()
            found.size == 1 -> callContact(contacts[found[0]])
            else -> Ui.sheet(this, "Кому позвонить?", "Нашлось несколько: «$query»",
                found.take(8).map { i ->
                    Triple<Int, String, () -> Unit>(R.drawable.ic_person, contacts[i].name) { callContact(contacts[i]) }
                })
        }
    }

    private fun callContact(c: Contact) {
        val nums = if (c.numbers.isEmpty()) listOf(PhoneNum(-1L, c.number, "")) else c.numbers
        if (nums.size == 1) {
            Toast.makeText(this, "Звоню: " + c.name, Toast.LENGTH_SHORT).show()
            placeCall(nums[0].number)
        } else {
            Ui.sheet(this, c.name, "Какой номер набрать?", nums.map { n ->
                val lab = if (n.label.isNotBlank()) n.label + " · " else ""
                Triple<Int, String, () -> Unit>(R.drawable.ic_call, lab + Calls.formatNumber(n.number)) { placeCall(n.number) }
            })
        }
    }

    // ------------------------------------------------------------------ звонок и набор номера

    private fun placeCall(number: String) {
        if (number.isBlank()) return
        if (!Perms.has(this, Manifest.permission.CALL_PHONE)) {
            pendingCall = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось позвонить", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDial(): LinearLayout {
        val a = this
        // размер кнопок подгоняем под высоту экрана, чтобы кнопка вызова всегда помещалась
        val heightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val keySize = ((heightDp - 80f - 62f - 66f - 26f - 100f - 58f) / 5f).toInt().coerceIn(52, 80)
        maxSuggest = if (heightDp >= 760f) 5 else 3
        val p = LinearLayout(a)
        p.orientation = LinearLayout.VERTICAL
        p.gravity = Gravity.CENTER_HORIZONTAL
        p.background = Theme.windowDrawable(a)
        p.isClickable = true // не пропускать нажатия на списки под панелью

        val top = LinearLayout(a)
        top.gravity = Gravity.START
        top.addView(iconButton(R.drawable.ic_close) { closeDial() })
        p.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 8), Ui.dp(a, 8), 0, 0))

        // Окно номера: сюда можно вставить номер из буфера, скопировать его или набрать цифрами
        val field = LinearLayout(a)
        field.orientation = LinearLayout.HORIZONTAL
        field.gravity = Gravity.CENTER_VERTICAL
        field.background = Ui.rounded(Theme.card, Ui.dpf(a, 22f))
        field.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 6), 0)
        dialField = EditText(a)
        dialField.setTextColor(Theme.text)
        dialField.setHintTextColor(Theme.sub)
        dialField.hint = "Введите или вставьте номер"
        dialField.textSize = 24f
        dialField.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        dialField.isSingleLine = true
        dialField.inputType = android.text.InputType.TYPE_CLASS_PHONE
        dialField.showSoftInputOnFocus = false // цифры набираются кнопками ниже, вставка — долгим нажатием или кнопкой
        dialField.background = null
        dialField.setPadding(0, 0, 0, 0)
        dialField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (dialSyncing) return
                val raw = e?.toString() ?: ""
                val clean = cleanNumber(raw)
                dialed.setLength(0)
                dialed.append(clean)
                if (clean != raw) {
                    dialSyncing = true
                    dialField.setText(clean)
                    dialField.setSelection(clean.length)
                    dialSyncing = false
                }
                updateDial()
            }
        })
        field.addView(dialField, Ui.lp(0, Ui.dp(a, 56), 1f))
        field.addView(iconButton(R.drawable.ic_paste) { pasteNumber() }, Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)))
        field.addView(iconButton(R.drawable.ic_content_copy) {
            if (dialed.isEmpty()) Toast.makeText(a, "Сначала введите номер", Toast.LENGTH_SHORT).show() else copy(dialed.toString())
        }, Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)))
        p.addView(field, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), 0))
        dialHint = Ui.text(a, "", 16f, Theme.sub)
        dialHint.gravity = Gravity.CENTER
        p.addView(dialHint, Ui.lp(Ui.MATCH, Ui.dp(a, 26)))
        suggestBox = LinearLayout(a)
        suggestBox.orientation = LinearLayout.VERTICAL
        p.addView(suggestBox, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), 0))

        p.addView(View(a), Ui.lp(Ui.MATCH, 0, 1f))
        p.addView(
            KeypadView(a, keySize, Theme.text, Theme.card,
                onDown = { ch -> dialed.append(ch); updateDial() },
                onUp = {},
                onLong = { _ ->
                    if (dialed.isNotEmpty() && dialed.last() == '0') dialed.setLength(dialed.length - 1)
                    dialed.append('+')
                    updateDial()
                }),
            Ui.lp(Ui.WRAP, Ui.WRAP)
        )

        val bottom = LinearLayout(a)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER
        val addContact = Ui.roundButton(a, keySize, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_person_add, Theme.sub, 26)
        addContact.setOnClickListener {
            if (dialed.isEmpty()) {
                Toast.makeText(a, "Сначала введите номер", Toast.LENGTH_SHORT).show()
            } else {
                val i = Intent(a, ContactEditActivity::class.java)
                i.putExtra(ContactEditActivity.EXTRA_NUMBER, dialed.toString())
                startActivity(i)
            }
        }
        bottom.addView(addContact)
        val call = Ui.roundButton(a, keySize, Ui.oval(Theme.GREEN), R.drawable.ic_call, Color.WHITE, keySize * 2 / 5 + 3)
        call.setOnClickListener {
            val n = dialed.toString()
            if (n.isNotEmpty()) {
                placeCall(n)
                closeDial()
            }
        }
        bottom.addView(call, Ui.margins(Ui.lp(Ui.dp(a, keySize), Ui.dp(a, keySize)), Ui.dp(a, 16), 0, Ui.dp(a, 16), 0))
        val back = Ui.roundButton(a, keySize, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_backspace, Theme.sub, 26)
        back.setOnClickListener {
            if (dialed.isNotEmpty()) dialed.setLength(dialed.length - 1)
            updateDial()
        }
        back.setOnLongClickListener {
            dialed.setLength(0)
            updateDial()
            true
        }
        bottom.addView(back)
        p.addView(bottom, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 8), 0, Ui.dp(a, 20)))
        return p
    }

    /** Похожие номера из контактов и журнала: показываются, пока набираете. */
    private class Suggest(val title: String, val number: String, val sub: String, val rank: Int)

    private fun updateDial() {
        val a = this
        if (dialField.text.toString() != dialed.toString()) {
            dialSyncing = true
            dialField.setText(dialed.toString())
            dialField.setSelection(dialField.text.length)
            dialSyncing = false
        }
        val digits = dialed.filter { it.isDigit() }.toString()
        suggestBox.removeAllViews()
        if (digits.length < 2) {
            dialHint.text = ""
            return
        }
        val found = LinkedHashMap<String, Suggest>()
        fun consider(title: String, number: String, sub: String) {
            val d = number.filter { it.isDigit() }
            val key = Repo.numKey(number)
            if (key.isEmpty() || found.containsKey(key)) return
            val rank = when {
                d.startsWith(digits) || key.startsWith(digits) -> 0
                d.endsWith(digits) -> 1
                d.contains(digits) -> 2
                else -> return
            }
            found[key] = Suggest(title, number, sub, rank)
        }
        for (c in contacts) for (n in c.numbers) consider(c.name, n.number, if (n.label.isNotBlank()) n.label else "Контакт")
        for (e in log) if (e.number.isNotBlank()) consider(e.title, e.number, "Из журнала")
        val top = found.values.sortedBy { it.rank }.take(maxSuggest)
        dialHint.text = if (digits.length >= 3 && top.isEmpty()) "Нового номера нет в контактах" else if (top.isNotEmpty()) "Похожие номера" else ""
        for (sg in top) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0)
            row.background = Ui.ripple(Ui.rounded(Theme.card, Ui.dpf(a, 16f)), Ui.dpf(a, 16f))
            row.isClickable = true
            val pretty = Calls.formatNumber(sg.number)
            val t1 = Ui.text(a, sg.title, 15f, Theme.text, true)
            t1.maxLines = 1
            t1.ellipsize = TextUtils.TruncateAt.END
            row.addView(t1, Ui.lp(0, Ui.WRAP, 1f))
            if (sg.title != pretty) {
                val t2 = Ui.text(a, pretty, 13f, Theme.sub)
                t2.maxLines = 1
                row.addView(t2, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 10), 0, 0, 0))
            }
            row.setOnClickListener {
                dialed.setLength(0)
                dialed.append(sg.number)
                updateDial()
            }
            suggestBox.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.dp(a, 40)), 0, 0, 0, Ui.dp(a, 4)))
        }
    }

    /** Оставляет в номере только цифры, «+», «*» и «#» (убирает пробелы, скобки и дефисы). */
    private fun cleanNumber(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) if (ch.isDigit() || ch == '+' || ch == '*' || ch == '#') sb.append(ch)
        return sb.toString()
    }

    private fun pasteNumber() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
        val clean = cleanNumber(text)
        if (clean.isEmpty()) {
            Toast.makeText(this, "В буфере обмена нет номера телефона", Toast.LENGTH_SHORT).show()
            return
        }
        dialed.setLength(0)
        dialed.append(clean)
        updateDial()
    }

    private fun openDial(prefill: String) {
        dialed.setLength(0)
        dialed.append(prefill)
        updateDial()
        dialOpen = true
        dialPanel.visibility = View.VISIBLE
        dialPanel.translationY = resources.displayMetrics.heightPixels.toFloat()
        dialPanel.animate().translationY(0f).setDuration(220).start()
        showPage(page)
    }

    private fun closeDial() {
        dialOpen = false
        dialPanel.animate().translationY(resources.displayMetrics.heightPixels.toFloat()).setDuration(200).withEndAction {
            if (!dialOpen) dialPanel.visibility = View.GONE
        }.start()
        showPage(page)
    }
}
