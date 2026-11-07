package lv.bibliogid.manga.core.text

import android.content.res.AssetManager
import java.io.File

/**
 * Частотный словарь русского языка (~47 000 словоформ) из assets/ru_freq.txt.
 *
 * Зачем: локальный OCR (PaddleOCR cyrillic) узнаёт буквы хорошо, но **плохо ставит пробелы** —
 * на выходе «тыдолжнабыласказатьпривет». Словарь позволяет восстановить границы слов
 * динамическим программированием по частотам, а не «на глазок».
 */
object WordFrequencies {

    const val ASSET = "ru_freq.txt"

    @Volatile private var freqMap: Map<String, Double> = emptyMap()
    @Volatile private var total: Double = 1.0

    val isLoaded: Boolean get() = freqMap.isNotEmpty()
    val size: Int get() = freqMap.size

    fun load(assets: AssetManager) {
        if (isLoaded) return
        runCatching {
            assets.open(ASSET).bufferedReader().use { loadFrom(it.readLines()) }
        }
    }

    /** Для тестов и для загрузки из файла. */
    fun loadFromFile(file: File) {
        if (!file.exists()) return
        runCatching { file.bufferedReader().use { loadFrom(it.readLines()) } }
    }

    fun loadFrom(lines: List<String>) {
        val map = HashMap<String, Double>(lines.size * 2)
        var sum = 0.0
        for (line in lines) {
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val w = line.substring(0, sp).trim().lowercase().replace('ё', 'е')
            if (w.isEmpty()) continue
            val c = line.substring(sp + 1).trim().toDoubleOrNull() ?: continue
            map[w] = c
            sum += c
        }
        if (map.isEmpty()) return
        freqMap = map
        total = if (sum > 0) sum else 1.0
    }

    fun contains(word: String): Boolean = freqMap.containsKey(normalize(word))

    /** Все словарные словоформы (для построения основ и морфологической проверки). */
    fun words(): Set<String> = freqMap.keys

    fun count(word: String): Double = freqMap[normalize(word)] ?: 0.0

    /** log-вероятность слова (для DP). */
    fun logProb(word: String): Double {
        val c = freqMap[normalize(word)] ?: return Double.NEGATIVE_INFINITY
        return Math.log(c) - Math.log(total)
    }

    fun logTotal(): Double = Math.log(total)

    private fun normalize(w: String) = w.lowercase().replace('ё', 'е')
}
