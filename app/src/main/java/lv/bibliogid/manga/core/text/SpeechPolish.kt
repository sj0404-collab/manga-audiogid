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

        // 1) «При творите сь» -> «Притворитесь», «Ха-» «ха.» -> «Ха-ха.» (склейка + регистр)
        var s = WordMender.mend(text)
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
        return Result(s, fixes)
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
