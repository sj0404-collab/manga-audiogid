package lv.bibliogid.manga.core.ocr

import lv.bibliogid.manga.core.text.WordFrequencies

/**
 * Страховка «ни одна строка не пропала».
 *
 * Журнал 1.6.16: когда два облачка слипались в один регион, у первой фразы
 * не дочитывались последние строки — текст реплики собирался, а часть строк
 * региона в него не попадала. Здесь итоговый текст сверяется со строками региона
 * по «ключам» слов (первые 4 буквы), и пропавшие строки дописываются.
 *
 * Дописываем осторожно: строку, в которой нет хотя бы двух слов по три буквы или
 * половина слов незнакома словарю, не трогаем — это мусор OCR, а не реплика.
 */
object LostLines {

    private val WORD = Regex("[\\p{L}]{3,}")

    /** Ключи слов строки: первые 4 буквы, нижний регистр, ё → е. */
    private fun keys(text: String): List<String> = WORD.findAll(text)
        .map { it.value.lowercase().replace('ё', 'е').take(4) }
        .toList()

    /** Кэш трёхбуквенных начал словаря: строим один раз на загрузку (47 тыс. слов). */
    @Volatile private var prefixSize: Int = -1
    @Volatile private var prefixes: Set<String> = emptySet()

    private fun ensurePrefixes() {
        if (prefixSize == WordFrequencies.size) return
        prefixes = WordFrequencies.words().mapTo(HashSet()) { it.take(3) }
        prefixSize = WordFrequencies.size
    }

    private fun knownKey(key: String): Boolean {
        if (WordFrequencies.contains(key)) return true
        if (!WordFrequencies.isLoaded) return false
        ensurePrefixes()
        return prefixes.contains(key)
    }

    /** Строки региона, которых нет в итоговом тексте реплики. */
    fun findLost(joined: String, rawLines: List<String>): List<String> {
        if (rawLines.isEmpty()) return emptyList()
        val have = keys(joined).toSet()
        val out = ArrayList<String>(1)
        for (line in rawLines) {
            val k = keys(line)
            if (k.size < 2) continue
            val found = k.count { it in have }
            if (found * 2 >= k.size) continue          // строка на месте
            // мусорную строку не возвращаем: половина слов должна быть словарной
            if (WordFrequencies.isLoaded && k.count { knownKey(it) } * 2 < k.size) continue
            out.add(line.trim())
        }
        return out
    }
}
