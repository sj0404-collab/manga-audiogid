package lv.bibliogid.manga.core.text

/**
 * Подготовка текста для системного TTS.
 *
 * Android TTS читает СЛОВО ИЗ ЗАГЛАВНЫХ как аббревиатуру — по буквам. Отсюда «КИМ МУВОН»
 * звучал как «ким м у вэон». Здесь:
 *  1. слова капсом, которых нет в частотном словаре (имена), переводятся в обычный вид
 *     «Мувон» — тогда голос произносит их как слово;
 *  2. последовательности одиночных букв («М У В О Н») склеиваются в слово;
 *  3. убираются лишние знаки, которые TTS читает вслух.
 */
object SpokenText {

    fun prepare(text: String): String {
        if (text.isBlank()) return text
        var s = text

        // одиночные буквы через пробел -> слово
        s = s.replace(Regex("\\b([А-ЯЁA-Z])(?:\\s+([А-ЯЁA-Z])){2,}\\b")) { m ->
            m.value.replace(" ", "")
        }

        // КАПС-слова: имена и всё, чего нет в словаре, — в обычный регистр
        s = s.split(' ').joinToString(" ") { token ->
            val core = token.trim { !it.isLetter() }
            if (core.length < 3) return@joinToString token
            val upper = core.count { it.isUpperCase() }
            val isShout = upper >= core.length - 1 && core.length >= 3
            if (!isShout) return@joinToString token
            val known = WordFrequencies.isLoaded && WordFrequencies.contains(core)
            // известные слова капсом (КРИК) оставляем как есть — TTS их прочитает нормально,
            // а вот незнакомые (имена) переводим в «Мувон», иначе их читают по буквам
            if (known) token else {
                val lower = core.lowercase()
                val nice = lower.replaceFirstChar { it.uppercaseChar() }
                token.replace(core, nice)
            }
        }
        return s
    }
}
