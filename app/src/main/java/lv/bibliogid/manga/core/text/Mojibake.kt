package lv.bibliogid.manga.core.text

/**
 * Восстановление «крякозябр» (mojibake) — типовые случаи:
 *  1) UTF-8 текст прочитан как CP1251:  «ÐŸÑ€Ð¸Ð²ÐµÑ‚» -> «Привет»
 *  2) CP1251 текст прочитан как Latin-1: «Ïðèâåò»         -> «Привет»
 *  3) Потерянные/заменённые символы U+FFFD.
 */
object Mojibake {

    fun looksBroken(s: String): Boolean {
        if (s.isEmpty()) return false
        val cyr = s.count { Chars.isCyrillic(it) }
        if (cyr > s.length / 3) return false
        return s.count { it in '\u00C0'..'\u00FF' } * 2 > s.length ||
            s.contains('\uFFFD') ||
            s.contains("Ð") || s.contains("Ñ") || s.contains("Ï") || s.contains("ð")
    }

    fun fix(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        if (out.contains('\uFFFD')) {
            // обрезаем «битые» куски, оставляя валидные
            out = out.replace("\uFFFD", "")
        }
        val candidates = listOf(
            { decode(out, "UTF-8", "windows-1251") },
            { decode(out, "windows-1251", "ISO-8859-1") },
            { decode(out, "UTF-8", "ISO-8859-1") }
        )
        var best = out
        var bestScore = score(out)
        for (c in candidates) {
            val fixed = runCatching { c() }.getOrNull() ?: continue
            if (fixed.isEmpty()) continue
            // «?» означает, что перекодировка не удалась — такие варианты отбрасываем
            if (fixed.count { it == '?' } > out.count { it == '?' }) continue
            val sc = score(fixed)
            if (sc > bestScore + 0.05) { best = fixed; bestScore = sc }
        }
        return best
    }

    private fun decode(s: String, to: String, from: String): String =
        String(s.toByteArray(charset(from)), charset(to))

    /** Доля «правильных» символов: кириллица, пробел, пунктуация, цифры. */
    fun score(s: String): Double {
        if (s.isEmpty()) return 0.0
        var good = 0
        for (c in s) {
            if (Chars.isCyrillic(c) || c.isDigit() || c == ' ' || c in ".,!?…:;—–-\"'()«»") good++
        }
        return good.toDouble() / s.length
    }
}

internal object Chars {
    fun isCyrillic(c: Char): Boolean = c in '\u0400'..'\u04FF'
    fun isLatin(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'
    fun isRussianLetter(c: Char): Boolean = c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё'
}
