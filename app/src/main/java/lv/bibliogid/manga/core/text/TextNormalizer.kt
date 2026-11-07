package lv.bibliogid.manga.core.text

/** Что делать с чисто латинскими токенами (OCR-мусор / английские вставки). */
enum class LatinPolicy {
    /** Выбрасывать — по умолчанию: в тексте не должно быть латиницы и английского. */
    DROP,
    /** Пробовать превратить в кириллицу по визуальному сходству. */
    HOMOGLYPH,
    /** Оставлять как есть. */
    KEEP
}

enum class CasePolicy { SENTENCE_CASE, KEEP_AS_IS }

data class NormalizeReport(
    val inputLines: Int = 0,
    val outputReplicas: Int = 0,
    val joinedLines: Int = 0,
    val replacedHomoglyphs: Int = 0,
    val repairedWords: Int = 0,
    val droppedTokens: Int = 0,
    val removedArtifacts: Int = 0,
    val mojibakeFixed: Boolean = false,
    val lostLines: Int = 0,
    val latinLeft: Int = 0,
    val warnings: List<String> = emptyList(),
    /** Строк, где пробелы восстановлены словарём. */
    val resplitLines: Int = 0
) {
    val isClean: Boolean get() = latinLeft == 0 && lostLines == 0
}

data class NormalizeResult(val replicas: List<String>, val report: NormalizeReport)

/**
 * Главный движок «чистого текста».
 *
 * Задача: из сырого OCR получить цельные русские предложения без крякозябр,
 * без латиницы/греческого/мусорных символов, без потери строк.
 *
 * Порядок обработки (каждый шаг покрыт юнит-тестами):
 *  1. mojibake (кодировки)
 *  2. удаление управляющих/невидимых символов
 *  3. чистка «мусора» (греческий, псевдографика, $ % ^ ~ и т.п.)
 *  4. гомоглифы латиница->кириллица внутри слов
 *  5. цифры/символы внутри слов -> буквы (словарь)
 *  6. политика латиницы (drop / homoglyph / keep)
 *  7. склейка переносов, пробелы, пунктуация
 *  8. сборка цельных предложений (склейка строк одного облачка)
 *  9. регистр + завершающая точка
 */
class TextNormalizer(
    private val latinPolicy: LatinPolicy = LatinPolicy.DROP,
    private val casePolicy: CasePolicy = CasePolicy.SENTENCE_CASE,
    private val lexicon: RussianLexicon = RussianLexicon.default(),
    private val ensureTerminalPunctuation: Boolean = true,
    /** REGION — строки одного облачка склеиваются всегда (используется в приложении). */
    private val joinMode: JoinMode = JoinMode.CONSERVATIVE
) {

    class Stats {
        var homoglyphs = 0
        var repaired = 0
        var dropped = 0
        var artifacts = 0
        var mojibake = false
        var resplit = 0
    }

    fun normalizeLine(raw: String, stats: Stats = Stats()): String = cleanLine(raw, stats)

    /** Нормализация набора строк (например, строк одного облачка или всей страницы). */
    fun normalize(rawLines: List<String>): NormalizeResult {
        val stats = Stats()
        val cleaned = ArrayList<String>(rawLines.size)
        for (line in rawLines) {
            var c = WordMender.mend(cleanLine(line, stats))
            // «двойники» шрифта: ТРЕНАЕ -> тренде, АЕЛО -> дело
            c = WordRepair.repairLine(c)
            // PP-OCR иногда не ставит пробелы: «тыдолжнабыласказатьпривет» -> «ты должна была сказать привет»
            if (c.isNotBlank() && WordSplitter.needsSplit(c)) {
                val resplit = WordSplitter.split(c)
                if (resplit.count { it == ' ' } > c.count { it == ' ' }) {
                    c = resplit
                    stats.resplit++
                }
            }
            cleaned.add(c)
        }
        // слово, разорванное переводом строки, склеиваем до сборки предложений
        val stitchedLines = LineStitcher.stitch(cleaned)
        val replicas = SentenceAssembler(lexicon, casePolicy, ensureTerminalPunctuation, joinMode).assemble(stitchedLines)
        val latinLeft = replicas.sumOf { r -> r.count { Homoglyphs.isLatinLetter(it) } }
        val warnings = ArrayList<String>()
        if (latinLeft > 0) warnings.add("в тексте осталась латиница: $latinLeft симв.")
        val lost = (rawLines.count { it.isNotBlank() } - replicas.size).coerceAtLeast(0)
        return NormalizeResult(
            replicas = replicas,
            report = NormalizeReport(
                inputLines = rawLines.size,
                outputReplicas = replicas.size,
                replacedHomoglyphs = stats.homoglyphs,
                repairedWords = stats.repaired,
                droppedTokens = stats.dropped,
                removedArtifacts = stats.artifacts,
                mojibakeFixed = stats.mojibake,
                latinLeft = latinLeft,
                warnings = warnings,
                resplitLines = stats.resplit
            )
        )
    }

    fun cleanLine(raw: String, stats: Stats = Stats()): String {
        if (raw.isBlank()) return ""
        var s = raw

        if (Mojibake.looksBroken(s)) {
            val fixed = Mojibake.fix(s)
            if (fixed != s) { s = fixed; stats.mojibake = true }
        }

        // 2. невидимые/управляющие символы
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val broken = ch.code < 0x20 && ch != '\n' && ch != '\t'
            val invisible = ch in '\u200B'..'\u200F' || ch == '\uFEFF' || ch == '\u00AD' ||
                ch in '\u202A'..'\u202E' || ch == '\uFFFD' || ch.isSurrogate()
            if (broken || invisible) { stats.artifacts++; continue }
            sb.append(ch)
        }
        s = sb.toString()

        // 3. мусорные символы -> пробел (греческий, псевдографика, $ % ^ ~ | и пр.)
        val sb2 = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '\u00A0' || ch == '\t' -> sb2.append(' ')
                Homoglyphs.isArtifact(ch) -> { stats.artifacts++; sb2.append(if (ch.isDigit()) ch else ' ') }
                ch.isLetterOrDigit() || ch == ' ' || Homoglyphs.isKeptPunctuation(ch) -> sb2.append(ch)
                else -> { stats.artifacts++; sb2.append(' ') }
            }
        }
        s = sb2.toString()

        // 4-6. по токенам
        val tokens = s.split(' ').filter { it.isNotEmpty() }
        val repaired = arrayOfNulls<String>(tokens.size)
        var anyKept = false
        val rawHasCyrillic = s.any { Chars.isCyrillic(it) || Chars.isRussianLetter(it) }
        for (idx in tokens.indices) {
            val t = repairToken(tokens[idx], stats)
            if (t.isNotEmpty()) { repaired[idx] = t; anyKept = true }
        }
        // Страховка: если токен выбросили как «латиницу», но он похож на кириллицу —
        // возвращаем его НА СВОЁ МЕСТО (иначе «AAAA! НЕ ТЯНИ» превращалось в «Не тяни. Аааа!»).
        if (!anyKept || rawHasCyrillic) {
            for (idx in tokens.indices) {
                if (repaired[idx] == null) {
                    val rescued = rescueLatinToken(tokens[idx], stats)
                    if (rescued.isNotEmpty()) repaired[idx] = rescued
                }
            }
        }
        s = repaired.filterNotNull().joinToString(" ")

        // 7. пробелы и пунктуация
        s = fixSpacing(s)
        return s.trim()
    }

    private fun repairToken(token: String, stats: Stats): String {
        var t = token
        val hasCyr = t.any { Chars.isCyrillic(it) || Chars.isRussianLetter(it) }
        val hasLat = t.any { Homoglyphs.isLatinLetter(it) }
        val hasDigit = t.any { it.isDigit() }

        if (hasLat && hasCyr) {
            val mapped = StringBuilder()
            for (ch in t) {
                val m = Homoglyphs.toCyrillic(ch)
                if (m != null) { mapped.append(m); stats.homoglyphs++ } else mapped.append(ch)
            }
            t = mapped.toString()
        } else if (hasLat && !hasCyr) {
            when (latinPolicy) {
                LatinPolicy.DROP -> {
                    val letters = t.count { it.isLetter() }
                    val digits = t.count { it.isDigit() }
                    if (letters >= 2) { stats.dropped++; return "" }
                    if (letters + digits == 0 && t.isNotEmpty()) return ""
                    if (letters == 1 && digits == 0) { stats.dropped++; return "" }
                }
                LatinPolicy.HOMOGLYPH -> {
                    val mapped = StringBuilder()
                    var allMapped = true
                    for (ch in t) {
                        val m = Homoglyphs.toCyrillic(ch)
                        if (m == null) { allMapped = false; mapped.append(ch) } else { mapped.append(m); stats.homoglyphs++ }
                    }
                    t = mapped.toString()
                    if (!allMapped) { stats.dropped++; return "" }
                }
                LatinPolicy.KEEP -> Unit
            }
        }

        // цифры/символы внутри русского слова
        val strikeFromOcr = t.any { it.isDigit() }
        if (strikeFromOcr && t.count { Chars.isRussianLetter(it) } >= 2) {
            val repairedWord = repairDigitsInsideWord(t)
            if (repairedWord != null) {
                stats.repaired++
                t = repairedWord
            } else {
                val lettersOnly = t.filter { !it.isDigit() }
                if (lettersOnly.length >= 2) {
                    // пробуем словарь с допуском 1-2 правки (слово уже битое OCR)
                    val fuzzy = lexicon.fuzzy(lettersOnly, if (lettersOnly.length >= 8) 3 else 1)
                    if (fuzzy != null) {
                        stats.repaired++
                        t = restoreCase(lettersOnly, fuzzy)
                    } else {
                        t = lettersOnly
                        stats.artifacts++
                    }
                }
            }
        }
        return t
    }

    /**
     * Спасает токен, который политика латиницы сочла «мусором», но который на самом
     * деле — русское слово, распознанное латинскими двойниками (Kим MуBон).
     * Возвращает кириллический вариант либо пустую строку, если спасать нечего.
     */
    private fun rescueLatinToken(token: String, stats: Stats): String {
        val letters = token.filter { it.isLetter() }
        if (letters.length < 2) return ""
        var mappedCount = 0
        val sb = StringBuilder(token.length)
        for (ch in token) {
            val m = Homoglyphs.toCyrillic(ch)
            if (m != null) { mappedCount++; sb.append(m) } else sb.append(ch)
        }
        // слишком мало визуальных двойников — это правда английское слово, не трогаем
        if (mappedCount.toFloat() / letters.length < 0.6f) return ""
        var out = sb.toString()
        // остатки латиницы: сначала словарь по похожему слову, иначе выкидываем
        if (out.any { Chars.isLatin(it) }) {
            val fuzzy = lexicon.fuzzy(out.filter { !Chars.isLatin(it) }.lowercase(), 2)
            out = if (fuzzy != null) restoreCase(out, fuzzy)
            else out.filter { !Chars.isLatin(it) }
        }
        out = out.trim().trim { !it.isLetterOrDigit() && it !in ".,!?…:;—\"()«»" }
        if (out.count { it.isLetter() } < 2) return ""
        stats.homoglyphs++
        return out
    }

    private fun repairDigitsInsideWord(t: String): String? {
        val mask = ArrayList<Char?>(t.length)
        for (ch in t) mask.add(if (ch.isDigit()) null else ch.lowercaseChar())
        val candidates = lexicon.maskMatches(mask)
        if (candidates.size == 1) return restoreCase(t, candidates[0])
        // прямое сопоставление по форме цифры
        val mapped = StringBuilder()
        for (ch in t) {
            val m = Homoglyphs.digitToLetter(ch)
            mapped.append(m ?: ch)
        }
        val candidate = mapped.toString()
        if (candidates.isEmpty() && lexicon.contains(candidate)) return restoreCase(t, candidate)
        return null
    }

    private fun restoreCase(original: String, word: String): String {
        val letters = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        val isUpper = original.any { it in 'А'..'Я' || it == 'Ё' }
        if (!isUpper) return word
        return word.uppercase()
    }

    private fun fixSpacing(s: String): String {
        var r = s.replace(Regex("[ ]{2,}"), " ")
        r = r.replace(Regex(" ([.,!?…:;])"), "$1")
        r = r.replace(Regex("([(«„]) "), "$1")
        r = r.replace(Regex(" ([)»“])"), "$1")
        r = r.replace(Regex("[-—–]{2,}"), "—")
        r = r.replace(Regex("\\.{2,}"), "…")
        r = r.replace(Regex("!{2,}"), "!")
        r = r.replace(Regex("\\?{2,}"), "?")
        return r.trim()
    }

    /** Приводит регистр к нормальному виду (OCR любит отдавать всё капсом). */
    fun normalizeCase(sentence: String): String {
        if (sentence.isEmpty()) return sentence
        if (casePolicy == CasePolicy.KEEP_AS_IS) return sentence
        val letters = sentence.count { it.isLetter() }
        if (letters == 0) return sentence
        val upper = sentence.count { it.isUpperCase() }
        val isShout = letters >= 3 && upper >= letters * 0.8
        if (!isShout) return sentence
        val lower = sentence.lowercase()
        val first = lower.indexOfFirst { it.isLetter() }
        if (first < 0) return lower
        return lower.substring(0, first) + lower[first].uppercaseChar() + lower.substring(first + 1)
    }
}
