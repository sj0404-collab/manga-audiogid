package lv.bibliogid.manga.core.text

/**
 * Лечение «рваных» слов и кривого регистра, которые оставляет OCR.
 *
 * Живые примеры с реальной страницы:
 *   «КОГДА УВИДЕЛА МЕН я.»            -> «когда увидела меня.»
 *   «Почему пр яч ешь с я?»           -> «почему прячешься?»
 *   «Ты должна бЫЛА СКАЗАТЬ ПРИВЕТ.»  -> «Ты должна была сказать привет.»
 *
 * Склейка идёт только там, где словарь подтверждает, что вместе получается слово,
 * поэтому имена и незнакомые слова («Ким Мувон») остаются как есть.
 */
object WordMender {

    private val SINGLE = setOf('в', 'и', 'а', 'с', 'к', 'у', 'о', 'я', 'б', 'ж', 'э')

    fun mend(line: String): String {
        if (line.isBlank()) return line
        val tokens = line.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (tokens.size < 2) return fixCase(line)
        var i = 0
        var guard = 0
        while (i < tokens.size - 1 && guard++ < 200) {
            // «Ха-» «ха-» «ха.» -> «Ха-ха-ха.»: слово разорвано пробелом после дефиса
            if (tokens[i].endsWith("-") && tokens[i + 1].isNotEmpty()) {
                tokens[i] = tokens[i] + tokens[i + 1]
                tokens.removeAt(i + 1)
                continue
            }
            var merged = false
            val maxJ = minOf(i + 3, tokens.size - 1)
            for (j in (i + 1)..maxJ) {
                val parts = tokens.subList(i, j + 1)
                // цифры и знаки не приклеиваем к словам: «5» + «ЙЕН» — это не «5йен»
                if (parts.any { letters(it).isEmpty() }) continue
                val joinedLetters = letters(parts.joinToString(""))
                if (joinedLetters.length <= 2) continue
                if (!WordFrequencies.contains(joinedLetters)) continue
                // все куски — самостоятельные слова? тогда не трогаем («Ким Мувон»)
                val allRealWords = parts.all { it.length > 2 && WordFrequencies.contains(letters(it)) }
                if (allRealWords) continue
                // среди кусков должен быть огрызок (иначе это просто два слова)
                if (parts.none { letters(it).length <= 2 }) continue
                var mergedToken = parts.joinToString("") { it }
                // пунктуация последнего куска сохраняется, лишняя из середины уходит
                mergedToken = mergedToken.replace(Regex("[.,!?…:;]+(?=.)"), "")
                tokens[i] = mergedToken
                var removed = 0
                for (k in j downTo i + 1) { tokens.removeAt(k); removed++ }
                merged = removed > 0
                break
            }
            if (!merged) i++
        }
        return fixCase(tokens.joinToString(" "))
    }

    /** Слова с заглавной в середине («бЫЛА», «прЯчешься») приводим к нижнему регистру. */
    fun fixCase(line: String): String {
        if (line.isEmpty()) return line
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (!ch.isLetter()) { out.append(ch); i++; continue }
            val start = i
            while (i < line.length && (line[i].isLetter() || line[i] == '-')) i++
            val word = line.substring(start, i)
            out.append(fixWordCase(word))
        }
        return out.toString()
    }

    private fun fixWordCase(word: String): String {
        val letters = word.filter { it.isLetter() }
        if (letters.length < 2) return word
        val upper = letters.count { it.isUpperCase() }
        if (upper == 0) return word
        if (upper == letters.length) return word          // весь КАПС — решит сборщик предложений
        // первая заглавная + остальные строчные — нормально
        if (word[0].isUpperCase() && upper == 1) return word
        // «бЫЛА», «МЕНя» — порча регистра; заглавную вернёт сборщик предложений
        return word.lowercase()
    }

    private fun letters(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch.isLetter()) sb.append(ch.lowercaseChar())
        return sb.toString()
    }
}
