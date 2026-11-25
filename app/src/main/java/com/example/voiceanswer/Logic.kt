package com.example.voiceanswer

import java.util.Locale

/** Чистая логика без Android — её можно проверить обычным тестом. */
object Logic {

    val DEFAULT_ACCEPT = listOf(
        "принять", "прими", "принимаю", "ответить", "ответ", "отвечай", "возьми трубку", "подними трубку"
    )
    val DEFAULT_REJECT = listOf(
        "отклонить", "отклони", "отмена", "отменить", "сбросить", "сбрось", "отбой"
    )

    enum class Command { ACCEPT, REJECT }

    private val RU = Locale.forLanguageTag("ru")
    private val junk = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")

    /** Нижний регистр, «ё» → «е», без знаков препинания, одиночные пробелы. */
    fun normalize(s: String): String =
        s.lowercase(RU).replace('ё', 'е').replace(junk, " ").replace(spaces, " ").trim()

    private fun firstIndex(phrase: String, words: List<String>, fuzzy: Boolean): Int {
        var best = -1
        for (w in words) {
            val n = normalize(w)
            if (n.isEmpty()) continue
            var i = phrase.indexOf(n)
            if (i < 0 && fuzzy && !n.contains(' ')) i = fuzzyIndex(phrase, n)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }

    /** Похожее слово: распознавание часто ошибается на одну-две буквы («приму» вместо «прими»). */
    private fun fuzzyIndex(phrase: String, word: String): Int {
        val allowed = when {
            word.length >= 11 -> 2
            word.length >= 5 -> 1
            else -> 0
        }
        if (allowed == 0 && word.length < 6) return -1
        var pos = 0
        for (t in phrase.split(' ')) {
            if (t.isNotEmpty() && Math.abs(t.length - word.length) <= allowed && lev(t, word) <= allowed) return pos
            // та же основа с другим окончанием: «отвечаю», «сбросить» ~ «сброс»
            if (word.length >= 6 && t.length >= word.length - 2 && t.startsWith(word.take(word.length - 2))) return pos
            pos += t.length + 1
        }
        return -1
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /**
     * Ищет слово-команду в списке вариантов распознавания (первый — самый вероятный).
     * Берётся первый вариант, где что-то нашлось; если в нём есть и «принять», и «отклонить» —
     * побеждает то, что сказано раньше.
     */
    fun match(phrases: List<String>, accept: List<String>, reject: List<String>): Command? {
        for (raw in phrases) {
            val p = normalize(raw)
            if (p.isEmpty()) continue
            var a = firstIndex(p, accept, false)
            var r = firstIndex(p, reject, false)
            if (a < 0 && r < 0) {
                // точного слова нет — пробуем похожие (распознавание ошибается на букву)
                a = firstIndex(p, accept, true)
                r = firstIndex(p, reject, true)
            }
            if (a < 0 && r < 0) continue
            return if (r >= 0 && (a < 0 || r <= a)) Command.REJECT else Command.ACCEPT
        }
        return null
    }

    /** Приводит слово из настроек к виду, в котором оно хранится. Пусто — если слово не годится. */
    fun cleanWord(s: String): String = normalize(s).take(40)

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return if (m100 in 11..19) many else when (m10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
    }

    /** «89967350717» → «8, 9 9 6, 7 3 5, 0 7, 1 7» — чтобы голос читал номер по цифрам. */
    fun spellNumber(raw: String): String {
        val d = raw.filter { it.isDigit() }
        if (d.isEmpty()) return ""
        val sizes: List<Int> = when (d.length) {
            11 -> listOf(1, 3, 3, 2, 2)
            10 -> listOf(3, 3, 2, 2)
            else -> List((d.length + 2) / 3) { 3 }
        }
        val parts = ArrayList<String>()
        var pos = 0
        for (s in sizes) {
            if (pos >= d.length) break
            val end = minOf(pos + s, d.length)
            parts.add(d.substring(pos, end).toList().joinToString(" "))
            pos = end
        }
        return parts.joinToString(", ")
    }

    fun formatDuration(totalSec: Long): String {
        val s = if (totalSec < 0) 0 else totalSec
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    /** Буквы для аватарки: «Дядя Сережа» → «ДС», «Мама» → «М», номер → пусто. */
    fun initials(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val words = name.trim().split(" ").filter { it.isNotEmpty() }
        val letters = words.mapNotNull { w -> w.firstOrNull { it.isLetter() } }
        if (letters.isEmpty()) return ""
        return letters.take(2).joinToString("").uppercase(RU)
    }

    // ---- «Позвони Маме» ----

    private val CALL_WORDS = setOf(
        "позвони", "позвонить", "звони", "набери", "набрать", "вызови", "вызвать", "звонок", "call"
    )
    private val FILLER = setOf("по", "имени", "абоненту", "контакту", "в", "на", "мне", "пожалуйста", "и", "позвони")

    /** Из фразы «позвони маме» достаёт «маме». null — это не команда звонка. */
    fun parseCall(phrase: String): String? {
        val words = normalize(phrase).split(' ').filter { it.isNotEmpty() }
        val i = words.indexOfFirst { it in CALL_WORDS }
        if (i < 0) return null
        val rest = words.drop(i + 1).dropWhile { it in FILLER }
        val name = rest.joinToString(" ")
        return if (name.isEmpty()) null else name
    }

    /** Сходство слов с учётом русских окончаний: «маме» ~ «мама», «ивану» ~ «иван». */
    fun wordMatches(q: String, w: String): Boolean {
        if (q == w) return true
        if (q.length < 3 || w.length < 3) return false
        var common = 0
        while (common < q.length && common < w.length && q[common] == w[common]) common++
        if (common >= 3 && common >= minOf(q.length, w.length) - 2) return true
        val allowed = if (maxOf(q.length, w.length) >= 4) 1 else 0
        if (allowed > 0 && Math.abs(q.length - w.length) <= allowed && lev(q, w) <= allowed) return true
        // «Федотову» ~ «Седотов»: сравниваем основы (без окончаний) со слиянием парных согласных
        val n = minOf(q.length, w.length) - 1
        if (n >= 5 && Math.abs(q.length - w.length) <= 3) {
            val fa = fold(q).take(n)
            val fb = fold(w).take(n)
            if (lev(fa, fb) <= (if (n >= 9) 2 else 1)) return true
        }
        return false
    }

    /** Звонкие и глухие согласные распознаватель путает чаще всего. */
    private fun fold(w: String): String {
        val sb = StringBuilder()
        for (ch in w) sb.append(
            when (ch) {
                'б' -> 'п'; 'в' -> 'ф'; 'г' -> 'к'; 'д' -> 'т'; 'ж' -> 'ш'; 'з' -> 'с'
                else -> ch
            }
        )
        return sb.toString()
    }

    /**
     * Индексы подходящих имён по запросу (лучшие первыми). Все слова запроса должны найтись среди слов имени.
     * Точное совпадение всей строки важнее частичных.
     */
    /** Слова, которые распознаватель часто путает с «дядя» («дядя» → «10», «десять», «дедя»). */
    private val DYADYA_ALIASES = setOf("10", "десять", "дедя", "дядь", "дядя", "дядю", "дяде", "дядей")

    private fun fixHeard(w: String): String = if (w in DYADYA_ALIASES) "дядя" else w

    fun findNames(query: String, names: List<String>): List<Int> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qw = q.split(' ').map { fixHeard(it) }
        val exact = ArrayList<Int>()
        val soft = ArrayList<Int>()
        val partial = ArrayList<Pair<Int, Int>>() // индекс, сколько слов совпало
        for ((idx, n) in names.withIndex()) {
            val nn = normalize(n)
            if (nn.isEmpty()) continue
            val nw = nn.split(' ').map { fixHeard(it) }
            if (nn == q || nw == qw) {
                exact.add(idx)
                continue
            }
            if (qw.all { w -> nw.any { wordMatches(w, it) } }) {
                soft.add(idx)
                continue
            }
            // слабое совпадение: часть слов запроса (остальные могли быть распознаны неверно)
            val hit = qw.count { w -> w.length >= 3 && !w.all { it.isDigit() } && w != "дядя" && nw.any { wordMatches(w, it) } }
            if (hit > 0) partial.add(idx to hit)
        }
        if (exact.isNotEmpty()) return exact
        if (soft.isNotEmpty()) return soft
        val best = partial.maxOfOrNull { it.second } ?: return emptyList()
        return partial.filter { it.second == best }.map { it.first }
    }

    /** «Абобус позвони маме» → «маме». null — слова-активатора нет или после него нет команды звонка. */
    fun parseWake(phrase: String, wake: String): String? {
        val w = normalize(wake)
        if (w.isEmpty()) return null
        val words = normalize(phrase).split(' ').filter { it.isNotEmpty() }
        val ww = w.split(' ')
        for (i in 0..(words.size - ww.size)) {
            var ok = true
            for (k in ww.indices) if (!wakeWordMatches(words[i + k], ww[k])) ok = false
            if (ok) return parseCall(words.drop(i + ww.size).joinToString(" "))
        }
        return null
    }

    private fun wakeWordMatches(heard: String, wake: String): Boolean {
        if (heard == wake) return true
        val allowed = when {
            wake.length >= 8 -> 2
            wake.length >= 5 -> 1
            else -> 0
        }
        return allowed > 0 && Math.abs(heard.length - wake.length) <= allowed && lev(heard, wake) <= allowed
    }
}
