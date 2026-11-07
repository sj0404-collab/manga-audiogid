package lv.bibliogid.manga.core.text

/**
 * Склейка строк OCR в цельные предложения.
 *
 * OCR режет одно облачко на 3-6 строк, часто с переносами («СЛУША-» / «ТЕ ЕЁ»).
 * Ассемблер собирает их обратно, опираясь на:
 *  - пунктуацию конца строки,
 *  - регистр первой буквы следующей строки,
 *  - «висячие» служебные слова в конце строки (и, а, но, что, в, на, не...),
 *  - дефис в конце строки (перенос).
 */
/** Как склеивать строки. */
enum class JoinMode {
    /** Каждая порция строк = одно облачко: клеим всё, кроме законченных предложений. */
    REGION,
    /** Без знания границ облачка: только по пунктуации/регистру/висячим словам. */
    CONSERVATIVE
}

class SentenceAssembler(
    private val lexicon: RussianLexicon = RussianLexicon.default(),
    private val casePolicy: CasePolicy = CasePolicy.SENTENCE_CASE,
    private val ensureTerminalPunctuation: Boolean = true,
    private val joinMode: JoinMode = JoinMode.CONSERVATIVE
) {

    private val terminal = charArrayOf('.', '!', '?', '…', ':', ';', '"', ')', '»', '“')
    private val dangling = setOf(
        "и", "а", "но", "что", "как", "в", "во", "на", "с", "со", "не", "то", "же", "ли",
        "по", "к", "у", "о", "об", "от", "до", "за", "из", "или", "чтобы", "если", "когда",
        "мы", "вы", "ты", "я", "он", "она", "они", "это", "весь", "все", "бы", "для", "при",
        "чем", "без", "над", "под", "между", "только", "уже", "ещё", "еще"
    )

    fun assemble(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var buf: StringBuilder? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (buf == null) {
                buf = StringBuilder(line)
                continue
            }
            val prev = buf.toString()
            if (shouldJoin(prev, line)) {
                buf = StringBuilder(join(prev, line))
            } else {
                out.add(finish(prev))
                buf = StringBuilder(line)
            }
        }
        buf?.let { if (it.isNotBlank()) out.add(finish(it.toString())) }
        return out.filter { it.isNotBlank() }
    }

    fun shouldJoin(prev: String, next: String): Boolean {
        if (prev.isEmpty()) return true
        val p = prev.trimEnd()
        if (p.isEmpty()) return true
        val lastChar = p.last()
        val firstLetter = next.firstOrNull { it.isLetter() }

        // перенос через дефис: «СЛУША-» + «ТЕ ЕЁ»
        if (lastChar == '-' || lastChar == '\u00AD') return true

        val endsSentence = lastChar in terminal
        val nextLower = firstLetter != null && firstLetter.isLowerCase()
        val prevLastWord = p.split(' ', '\n').lastOrNull()?.trim { !it.isLetter() }?.lowercase()

        if (!endsSentence) {
            if (joinMode == JoinMode.REGION) return true
            if (nextLower) return true
            if (prevLastWord != null && prevLastWord in dangling) return true
            // предыдущая строка слишком короткая — почти наверняка часть предложения
            if (p.count { it.isLetter() } <= 2) return true
        }
        return false
    }

    private fun join(prev: String, next: String): String {
        var p = prev.trimEnd()
        val hyphenJoin = p.endsWith("-") || p.endsWith("\u00AD")
        if (hyphenJoin) {
            p = p.dropLast(1).trimEnd()
            return p + next.trimStart()
        }
        return p + " " + next.trimStart()
    }

    private fun finish(sentence: String): String {
        var s = sentence.trim()
        s = s.replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return s
        // убираем одиночные мусорные знаки в начале/конце
        s = s.trim { it == ',' || it == ';' || it == '-' || it == '—' || it == ':' || it == '.' && s.length > 1 }
        if (s.isEmpty()) return s
        s = applyCase(s)
        s = restoreCommas(s)
        if (ensureTerminalPunctuation && !s.last().let { it in terminal }) {
            val words = s.count { it.isLetter() }
            if (words >= 2) s += "."
        }
        return s
    }

    /** Разбивает реплику по предложениям и выравнивает регистр в каждом. */
    private fun normalizeCase(s: String): String {
        val sb = StringBuilder(s.length)
        var start = 0
        for (i in s.indices) {
            val ch = s[i]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\u2026') {
                sb.append(oneSentence(s.substring(start, i + 1)))
                start = i + 1
            }
        }
        if (start < s.length) sb.append(oneSentence(s.substring(start)))
        return sb.toString()
    }

    private fun oneSentence(part: String): String {
        if (part.isBlank()) return part
        val letters = part.count { it.isLetter() }
        if (letters == 0) return part
        val upper = part.count { it.isUpperCase() }
        // всё предложение капсом (крик или типичная манга-наборка) — в обычный регистр
        if (letters >= 3 && upper >= letters * 0.8) return part.lowercase()
        return fixWordCase(part)
    }

    /**
     * Слово целиком капсом («ВЫ», «НЕ», «КИМ») или слово с заглавной ВНУТРИ
     * («БУДЕте», «РАВНОдушным», «должНЫ») — артефакт шрифта/OCR, а не имя:
     * приводим к строчным. Заглавная в начале слова (имена, начало фразы) не трогается.
     */
    private fun fixWordCase(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (!ch.isLetter()) { out.append(ch); i++; continue }
            val start = i
            while (i < s.length && (s[i].isLetter() || s[i] == '-')) i++
            val word = s.substring(start, i)
            val letters = word.count { it.isLetter() }
            val upper = word.count { it.isUpperCase() }
            val allCaps = letters >= 2 && upper == letters
            val innerCaps = letters >= 2 && word.drop(1).any { it.isUpperCase() }
            out.append(if (allCaps || innerCaps) word.lowercase() else word)
        }
        return out.toString()
    }

    /**
     * Точка вместо запятой — частая ошибка OCR в этом шрифте: «НАЧИНАТЬ ВСТРЕЧУ, ТАК ЧТО…»
     * распознаётся как «встречу. Так что…». Если после точки идёт слово, продолжающее фразу
     * («что», «чтобы», «чем», «который…»), ставим запятую: «то, что вы должны делать».
     */
    private fun restoreCommas(s: String): String {
        val re = Regex(
            "([.!?\u2026])\\s+(что|чтобы|чем|так что|котор\\p{L}*)",
            RegexOption.IGNORE_CASE
        )
        return re.replace(s) { m ->
            ", " + m.groupValues[2].replaceFirstChar { it.lowercase() }
        }
    }

    /**
     * Ровный регистр без «Не УВЕРЕН.», «БУДЕте», «ВЫСОЧЕСТВОМ».
     *
     * «Кричащей» считаем не всю реплику, а КАЖДОЕ предложение отдельно: облачко часто
     * начинается спокойной строкой «В будущем,» и продолжается капсом — раньше из-за
     * этого порог 80 % не срабатывал на всю реплику, и капс оставался в тексте и в озвучке.
     */
    fun applyCase(sentence: String): String {
        if (sentence.isEmpty()) return sentence
        val letters = sentence.count { it.isLetter() }
        if (letters == 0) return sentence
        val body = if (casePolicy == CasePolicy.KEEP_AS_IS) sentence else normalizeCase(sentence)
        // каждое новое предложение внутри реплики — с большой буквы
        val sb = StringBuilder(body.length)
        var capitalizeNext = true
        for (ch in body) {
            if (capitalizeNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\u2026') capitalizeNext = true
            }
        }
        var out = sb.toString()
        // «Не Уверен.», «говорить С Её высочеством» — заглавная внутри фразы у обычного
        // (словарного) слова. Имена в словаре частот нет, поэтому их не задеваем.
        out = lowerMidSentenceCapitals(out)
        return out
    }

    private fun lowerMidSentenceCapitals(s: String): String {
        if (!WordFrequencies.isLoaded) return s
        val out = StringBuilder(s.length)
        var sentenceStart = true
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (!ch.isLetter()) {
                out.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\u2026') sentenceStart = true
                i++
                continue
            }
            val start = i
            while (i < s.length && (s[i].isLetter() || s[i] == '-')) i++
            val word = s.substring(start, i)
            val isFirstInSentence = sentenceStart
            sentenceStart = false
            val startsUpper = word.first().isUpperCase()
            val restLower = word.drop(1).none { it.isUpperCase() }
            // только частые слова (порог отсекает имена: «Ким» в словаре есть, но редко)
            val known = WordFrequencies.count(word) >= 20_000.0
            out.append(
                if (!isFirstInSentence && startsUpper && restLower && known) word.lowercase() else word
            )
        }
        return out.toString()
    }
}
