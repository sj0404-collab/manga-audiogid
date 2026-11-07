package lv.bibliogid.manga.core.ocr

import lv.bibliogid.manga.core.text.WordFrequencies

/**
 * Склейка реплики, разрезанной границей страниц.
 *
 * Реальная жалоба по журналу: на стр.2 озвучивалось «Но разве.», а на стр.3 — «Наш лидер
 * уже недостаточно плох?». Это одно предложение, которое верстальщик разорвал страницами,
 * и голос читал его двумя кусками с падением интонации посередине.
 *
 * Признак «фраза не закончена» приходит из OCR: [Replica.openEnded] = в сыром тексте
 * в конце не было точки/!/?/многоточия. Тогда хвост текущей страницы склеивается
 * с первой репликой следующей — и озвучивается одной фразой.
 */
object CrossPage {

    /** Продолжением считаем только короткий обрывок — иначе рискуем слепить абзацы. */
    const val MAX_OPEN_LETTERS = 60

    /** Частота, выше которой слово «обычное» и его можно писать со строчной. */
    private const val COMMON_WORD_COUNT = 5000.0

    fun canJoin(prev: Replica?, next: Replica?): Boolean {
        if (prev == null || next == null) return false
        if (prev.silence || next.silence) return false
        if (!prev.openEnded) return false
        if (prev.text.isBlank() || next.text.isBlank()) return false
        if (prev.text.count { it.isLetter() } > MAX_OPEN_LETTERS) return false
        // следующая реплика должна быть настоящей фразой, а не «…» или «А!»
        if (next.text.count { it.isLetter() } < 2) return false
        return true
    }

    /** «Но разве.» + «Наш лидер уже недостаточно плох?» -> «Но разве наш лидер уже недостаточно плох?» */
    fun join(prev: String, next: String): String {
        var p = prev.trimEnd()
        if (p.endsWith(".")) p = p.dropLast(1).trimEnd()
        val n = lowerFirstIfCommon(next.trim())
        return (p + " " + n).replace(Regex("\\s{2,}"), " ").trim()
    }

    /**
     * Первое слово следующей страницы пишем со строчной, только если это ОБЫЧНОЕ слово.
     * Имена и незнакомые слова («Кай», «Алисия») остаются с заглавной.
     */
    private fun lowerFirstIfCommon(s: String): String {
        val first = s.firstOrNull() ?: return s
        if (!first.isUpperCase()) return s
        val word = s.takeWhile { it.isLetter() }
        if (word.length < 2) return s
        if (!WordFrequencies.isLoaded) return s
        val low = word.lowercase()
        if (WordFrequencies.count(low) < COMMON_WORD_COUNT) return s
        return low + s.substring(word.length)
    }
}
