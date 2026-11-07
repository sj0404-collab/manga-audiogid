package lv.bibliogid.manga.core.ocr

/**
 * Склейка фраз одного облачка в одну реплику — с восстановлением запятых.
 *
 * Ассемблер возвращает по предложению на фразу, а облачко должно читаться ЦЕЛЬНО.
 * При склейке двух фраз надо решить, что стояло на стыке — точка или запятая. В этом
 * шрифте OCR постоянно ставит точку вместо запятой, и без правки получается
 * «Уже пора начинать встречу. Так что её высочество…» и «Лучше быть. Более равнодушным.».
 *
 * Правила (оба — только для стыка ВНУТРИ облачка, последняя фраза не трогается):
 *  - следующая фраза начинается словом, продолжающим предложение («что», «чтобы», «чем»,
 *    «который…») — значит на стыке запятая;
 *  - фраза перед стыком совсем короткая (1–2 слова) — тоже запятая.
 */
object BubbleJoiner {

    /** Вводные слова: перед ними точка — почти всегда запятая («её высочество, должно быть, прибыла»). */
    private val parenthetical = setOf(
        "должно", "может", "кажется", "наверное", "конечно", "пожалуй", "видимо",
        "вероятно", "кстати", "впрочем", "правда"
    )

    private val continuation = setOf(
        "что", "чтобы", "чем", "который", "которая", "которое", "которые",
        "которым", "которой", "которых", "которому", "которую"
    )

    fun join(parts: List<String>): String {
        val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        if (cleaned.size == 1) return cleaned[0]
        val sb = StringBuilder(cleaned[0])
        for (i in 1 until cleaned.size) {
            val sep = separator(cleaned[i - 1], cleaned[i])
            if (sep == ", " && sb.isNotEmpty() && sb.last() == '.') sb.setLength(sb.length - 1)
            val part = if (sep == ", ") cleaned[i].replaceFirstChar { it.lowercase() } else cleaned[i]
            sb.append(sep).append(part)
        }
        return sb.toString()
    }

    private fun separator(current: String, next: String): String {
        if (!current.endsWith(".")) return " "
        if (words(current) in 1..2) return ", "
        val first = next.split(' ').firstOrNull { it.any { c -> c.isLetter() } }
            ?.trim { !it.isLetter() }?.lowercase()
        if (first != null && first in continuation) return ", "
        if (first != null && first in parenthetical) return ", "
        if (next.lowercase().startsWith("так что")) return ", "
        // фраза из одного слова внутри облачка — это продолжение, а не отдельное предложение
        if (words(next) == 1) return ", "
        return " "
    }

    private fun words(s: String): Int = s.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
}
