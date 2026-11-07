package lv.bibliogid.manga.core.text

/**
 * Последняя полировка реплики ПЕРЕД озвучкой.
 *
 * Зачем отдельный проход: постобработка OCR живёт в [TextNormalizer] и работает в момент
 * распознавания, а текст страницы может прийти из кеша — собранный прежней версией приложения.
 * Тогда пользователь слышит старые ошибки («сераце дракона», «Я поаумала», «небудешь»,
 * «Может лианна»), хотя правила починки давно есть.
 *
 * Поэтому перед синтезом речи реплика прогоняется ещё раз — теми же правилами, но
 * по-токенно и с сохранением регистра, а каждая правка пишется в журнал:
 *   `[tts] поправлено: «сераце» → «сердце», «небудешь» → «не будешь»`
 */
object SpeechPolish {

    data class Fix(val from: String, val to: String)

    data class Result(val text: String, val fixes: List<Fix>)

    /** Полировка строки. Без загруженного словаря ничего не меняем (юнит-тесты, старт). */
    fun polish(text: String): Result {
        if (text.isBlank() || !WordFrequencies.isLoaded) return Result(text, emptyList())
        val fixes = ArrayList<Fix>(2)

        // 0) «2-з раза» -> «2-3 раза»: OCR путает цифру 3 с буквой «з» после дефиса
        val digits = fixDigitDash(text)
        if (digits != text) fixes.add(Fix(text, digits))
        var s = digits

        // 1) «При творите сь» -> «Притворитесь», «Ха-» «ха.» -> «Ха-ха.» (склейка + регистр)
        s = WordMender.mend(s)
        if (s != text) fixes.add(Fix(text, s))

        // 2) двойники шрифта: «сераце» -> «сердце», «поаумала» -> «подумала»
        val repaired = WordRepair.repairLine(s)
        if (repaired != s) {
            recordDiff(s, repaired, fixes)
            s = repaired
        }

        // 3) слипшиеся слова: «небудешь» -> «не будешь», «лианна» -> «ли Анна», «побыстрому»
        val split = splitTokens(s)
        if (split != s) {
            recordDiff(s, split, fixes)
            s = split
        }

        // 4) пропавший пробел на стыке с союзом «и»: «Ему всего 11 лети я забыла»
        //    — OCR склеил «лет» + «и», а «лети» есть в словаре, поэтому обычное
        //    разлепливание молчало, и голос читал «лети» вместо «лет и».
        val gluedAnd = fixGluedConjunction(s)
        if (gluedAnd != s) {
            recordDiff(s, gluedAnd, fixes)
            s = gluedAnd
        }
        return Result(s, fixes)
    }

    /** Местоимения, после которых «…и» почти всегда = «… + и». */
    private val PRONOUNS = listOf("я", "он", "она", "они", "мы", "вы", "ты")

    /**
     * «лети я» -> «лет и я», «годи он» -> «год и он».
     *
     * Условия жёсткие, чтобы не портить нормальные слова:
     *  - слово кончается на «и», без него остаётся НАСТОЯЩЕЕ слово (а не обрубок словаря);
     *  - основа очень частая (в словаре > 5000) — «дети»/«люди» сюда не попадают,
     *    потому что «дет» в словаре нет, а «люд» встречается 268 раз;
     *  - следующее слово — местоимение («лети я», «лети он»).
     */
    private fun fixGluedConjunction(line: String): String {
        if (line.length < 6) return line
        val re = Regex("(\\p{L}{3,}и)\\s+(" + PRONOUNS.joinToString("|") + ")(?![\\p{L}])", RegexOption.IGNORE_CASE)
        return re.replace(line) { m ->
            val word = m.groupValues[1]
            // «Лети, я сказал.» (OCR потерял запятую) — не трогаем: слово в начале фразы
            val before = line.substring(0, m.range.first).trimEnd()
            if (before.isEmpty() || before.last() in ".!?…:;—-\"«") return@replace m.value
            val base = word.dropLast(1).lowercase().replace('ё', 'е')
            if (!WordFrequencies.contains(base)) return@replace m.value
            if (WordFrequencies.count(base) < 5000.0) return@replace m.value
            // основа должна кончаться на согласную: «лет и», «год и», но не «ночи и»
            if (base.last() in "аеиоуыэюяйьъ") return@replace m.value
            val tail = word.last()
            word.dropLast(1) + " " + tail + m.value.substring(word.length)
        }
    }

    /** Буквы, которые OCR ставит вместо цифры после дефиса: «2-з» вместо «2-3». */
    private val DIGIT_HOMOGLYPH = mapOf('з' to '3', 'б' to '6', 'ч' to '4')

    private fun fixDigitDash(text: String): String {
        val re = Regex("(\\d)\\s*[-–—]\\s*([збч])")
        return re.replace(text) { m -> m.groupValues[1] + "-" + DIGIT_HOMOGLYPH.getValue(m.groupValues[2][0]) }
    }

    /** Разбивает слипшиеся токены, сохраняя пунктуацию и заглавную букву. */
    private fun splitTokens(line: String): String {
        if (line.isBlank()) return line
        val out = StringBuilder(line.length + 8)
        var i = 0
        var first = true
        while (i < line.length) {
            val ch = line[i]
            if (ch.isWhitespace()) { out.append(ch); i++; continue }
            // знаки препинания вне слова
            if (!ch.isLetter()) { out.append(ch); i++; continue }
            val start = i
            while (i < line.length && (line[i].isLetter() || line[i] == '-')) i++
            val word = line.substring(start, i)
            val fixed = fixWord(word, atLineStart = first)
            out.append(fixed)
            first = false
        }
        return out.toString()
    }

    private fun fixWord(word: String, atLineStart: Boolean): String {
        if (word.contains('-')) return word                  // «ха-ха», «кто-то» не трогаем
        val low = word.lowercase().replace('ё', 'е')
        if (low.length < 3) return word
        // 1) частица приклеена: «Ноя» -> «Но я», «лианна» -> «ли Анна»
        WordSplitter.splitShort(low, atLineStart)?.let { return applyCase(word, it) }
        if (WordFrequencies.contains(low)) return word
        // 2) ровно два словарных слова: «небудешь» -> «не будешь»
        WordSplitter.splitInTwo(low)?.let { return applyCase(word, it) }
        // 3) длинный слипшийся кусок — только если КАЖДАЯ часть есть в словаре
        //    (иначе «обручаться» превращалось в «об ру ча тьс я»)
        if (low.length >= 10) {
            val parts = WordSplitter.splitCompact(low)
            if (parts.contains(' ') &&
                parts.split(' ').all { it.length == 1 || WordFrequencies.contains(it) }
            ) return applyCase(word, parts)
        }
        return word
    }

    private fun applyCase(original: String, split: String): String =
        if (original[0].isUpperCase()) split.replaceFirstChar { it.uppercaseChar() } else split

    /** Из двух строк достаём пары «было → стало» по токенам. */
    private fun recordDiff(before: String, after: String, fixes: MutableList<Fix>) {
        val a = before.split(' ')
        val b = after.split(' ')
        if (a.size == b.size) {
            for (k in a.indices) if (a[k] != b[k]) fixes.add(Fix(a[k], b[k]))
            return
        }
        fixes.add(Fix(before.trim(), after.trim()))
    }
}
