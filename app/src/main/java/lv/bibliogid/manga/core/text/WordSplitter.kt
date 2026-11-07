package lv.bibliogid.manga.core.text

/**
 * Восстановление пробелов и слов в слипшемся тексте OCR.
 *
 * PaddleOCR (кириллица) часто отдаёт строку без пробелов и с латинскими двойниками:
 *   «TыдOлЖHA6ылACKA3AтьпPивET»  ->  «ты должна была сказать привет»
 *
 * Алгоритм: сначала латинские двойники и цифры переводятся в кириллицу, затем строка
 * разбивается на слова динамическим программированием по частотам (WordFrequencies):
 * максимизируем сумму log P(слово). Неизвестные куски разрешены, но дороги —
 * поэтому «должна» выгоднее, чем «дол ж на».
 */
object WordSplitter {

    /** Однобуквенные слова, которые реально встречаются в русском тексте. */
    private val SINGLE_LETTER_WORDS = setOf('в', 'и', 'а', 'с', 'к', 'у', 'о', 'я', 'б', 'ж', 'э', 'ю')

    private val LAT = mapOf(
        'a' to 'а', 'o' to 'о', 'e' to 'е', 'c' to 'с', 'p' to 'р', 'x' to 'х', 'y' to 'у',
        'k' to 'к', 'h' to 'н', 'b' to 'в', 'm' to 'м', 't' to 'т', 'u' to 'и', 'n' to 'п',
        'r' to 'г', 'i' to 'і', 'l' to 'л', 'd' to 'д', 'f' to 'ф', 'g' to 'г', 'j' to 'ј',
        'q' to 'я', 's' to 'с', 'v' to 'в', 'w' to 'ш', 'z' to 'з'
    )

    private val DIGIT = mapOf(
        '0' to 'о', '3' to 'з', '4' to 'ч', '6' to 'б', '8' to 'в', '9' to 'д', '2' to 'з', '5' to 'с'
    )

    private const val UNKNOWN_CHAR_COST = -13.8   // ln(1e-6) за символ неизвестного слова
    private const val MAX_WORD = 20
    private const val PUNCT = ".,!?…:;—–-'()«»\"„“”"

    fun isPunct(c: Char): Boolean = PUNCT.indexOf(c) >= 0

    /** Кириллизация латиницы/цифр — без обращения к словарю. */
    fun toCyrillic(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val lower = ch.lowercaseChar()
            val mapped = LAT[lower] ?: DIGIT[ch]
            when {
                mapped != null -> sb.append(mapped)
                ch.isLetterOrDigit() || isPunct(ch) || ch == ' ' -> sb.append(lower)
                else -> sb.append(' ')
            }
        }
        return sb.toString()
    }

    /**
     * Нужно ли вообще разбивать строку: только если в ней есть длинный
     * (10+ букв) кусок, которого нет в словаре — то есть текст действительно слипся.
     */
    fun needsSplit(line: String): Boolean {
        if (!WordFrequencies.isLoaded) return false
        val tokens = line.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        return tokens.any { t ->
            val core = cleanToken(t)
            if (core.length >= 10 && !WordFrequencies.contains(core)) return@any true
            core.length >= 6 && splitInTwo(core) != null
        }
    }

    /** Главная операция: строка -> строка с восстановленными пробелами. */
    fun split(line: String): String {
        if (!WordFrequencies.isLoaded) return line
        val prepared = toCyrillic(line).trim()
        if (prepared.isEmpty()) return prepared
        val out = ArrayList<String>(8)
        for (piece in prepared.split(' ')) {
            if (piece.isBlank()) continue
            out.add(fixPiece(piece))
        }
        return out.joinToString(" ")
    }

    /** Правит один «кусок» между пробелами: пунктуацию не ломает, дефисы не рвёт. */
    private fun fixPiece(piece: String): String {
        if (piece.contains('-')) return piece          // «ха-ха-ха», «кто-то» — не трогаем
        val core = cleanToken(piece)
        if (core.isEmpty() || WordFrequencies.contains(core)) return piece
        val lead = piece.takeWhile { !it.isLetterOrDigit() }
        val trail = piece.takeLastWhile { !it.isLetterOrDigit() }
        // 1) слиплось несколько слов: «тыдолжна» -> «ты должна» (одна граница)
        splitInTwo(core)?.let { return lead + it + trail }
        // 2) длинный слипшийся кусок: полный DP по словарю
        if (core.length >= 10) {
            val split = splitCompact(core)
            if (split.contains(' ')) return lead + split + trail
        }
        return piece
    }

    /**
     * Пробует разрезать слипшееся слово ровно на два словарных слова.
     * Возвращает «левое правое» или null. Целое слово, которого нет в словаре,
     * но которое делится на два известных: «этодвое», «немогу», «тыдолжна».
     */
    fun splitInTwo(core: String): String? {
        if (!WordFrequencies.isLoaded) return null
        val s = core.lowercase().replace('ё', 'е')
        if (s.length < 6) return null
        if (WordFrequencies.contains(s)) return null       // это нормальное слово
        var bestScore = Double.NEGATIVE_INFINITY
        var bestAt = -1
        for (i in 2..s.length - 3) {
            val l = s.substring(0, i)
            val r = s.substring(i)
            if (!WordFrequencies.contains(l) || !WordFrequencies.contains(r)) continue
            val score = WordFrequencies.logProb(l) + WordFrequencies.logProb(r)
            if (score > bestScore) { bestScore = score; bestAt = i }
        }
        if (bestAt < 0) return null
        return s.substring(0, bestAt) + " " + s.substring(bestAt)
    }

    /** DP по одному «слипшемуся» фрагменту без пробелов. */
    fun splitCompact(compact: String): String {
        val s = compact.lowercase().replace('ё', 'е')
        val n = s.length
        if (n == 0) return ""
        if (n == 1) return s
        // короткие слова делить нечего: почти всегда это одно слово, а не слипшийся текст
        if (n < 10) return s
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 1..n) {
            var j = i - 1
            val limit = maxOf(0, i - MAX_WORD)
            while (j >= limit) {
                if (best[j] == Double.NEGATIVE_INFINITY) { j--; continue }
                val w = s.substring(j, i)
                val cost = wordCost(w)
                if (cost != null) {
                    val cand = best[j] + cost
                    if (cand > best[i]) { best[i] = cand; back[i] = j }
                }
                j--
            }
        }
        if (back[n] < 0) return s
        val parts = ArrayList<String>(8)
        var i = n
        while (i > 0) {
            val j = back[i]
            if (j < 0) { parts.add(s.substring(0, i)); break }
            parts.add(s.substring(j, i))
            i = j
        }
        parts.reverse()
        // Отсекаем «деление на огрызки»: «ЧЕШЬСЯ» -> «ч ешь с я» — это хуже, чем было.
        val singles = parts.count { it.length == 1 }
        val tooShort = parts.any { it.length == 1 && it[0] !in SINGLE_LETTER_WORDS }
        if (parts.size > 1 && (singles > 1 || tooShort)) return s
        return parts.joinToString(" ")
    }

    private fun wordCost(w: String): Double? {
        if (w.isEmpty()) return null
        if (w.length > MAX_WORD) return null
        if (WordFrequencies.contains(w)) return WordFrequencies.logProb(w)
        // неизвестное слово: допускаем, но чем длиннее, тем дороже
        if (w.length == 1) {
            return if (w[0] in SINGLE_LETTER_WORDS) -9.0 else UNKNOWN_CHAR_COST
        }
        return UNKNOWN_CHAR_COST * w.length
    }

    private fun cleanToken(t: String): String {
        val sb = StringBuilder(t.length)
        for (ch in toCyrillic(t)) if (ch.isLetter()) sb.append(ch)
        return sb.toString()
    }
}
