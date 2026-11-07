package lv.bibliogid.manga.core.text

/**
 * Склейка слова, разорванного переводом строки.
 *
 * В плашках-комментариях слово переносится без дефиса: строка кончается «КИМ МУВО»,
 * следующая начинается «НОМ ТО ОНИ…». Раньше это читалось как два слова — «муво» и
 * «ном», то есть последнее слово звучало дважды.
 *
 * Правило: если последнее слово строки не заканчивается знаком препинания и
 *  - склейка «последнее + первое» есть в словаре, или
 *  - оба куска по отдельности в словаре отсутствуют (значит это обрывок одного слова),
 * то пробел между ними не ставится.
 */
object LineStitcher {

    fun stitch(lines: List<String>): List<String> {
        if (lines.size < 2 || !WordFrequencies.isLoaded) return lines
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isBlank()) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val glued = tryGlue(prev, line)
                if (glued != null) {
                    out[out.size - 1] = glued
                    continue
                }
            }
            out.add(line)
        }
        return out
    }

    private fun tryGlue(prev: String, next: String): String? {
        val tail = prev.trimEnd()
        if (tail.isEmpty()) return null
        val lastChar = tail.last()
        if (!lastChar.isLetter()) return null            // «ПРОТИ-» обрабатывает WordMender
        val sp = tail.lastIndexOf(' ')
        val lastWord = if (sp < 0) tail else tail.substring(sp + 1)
        if (lastWord.length < 2) return null

        val firstWord = next.trimStart().takeWhile { it.isLetter() }
        if (firstWord.isEmpty()) return null

        val merged = (lastWord + firstWord).lowercase().replace('ё', 'е')
        val lastKnown = WordFrequencies.contains(lastWord)
        val firstKnown = WordFrequencies.contains(firstWord)

        val glue = WordFrequencies.contains(merged) ||
            (!lastKnown && !firstKnown && firstWord.length <= 4 && lastWord.length >= 3)
        if (!glue) return null
        val rest = next.trimStart().substring(firstWord.length)
        return tail + firstWord + rest
    }
}
