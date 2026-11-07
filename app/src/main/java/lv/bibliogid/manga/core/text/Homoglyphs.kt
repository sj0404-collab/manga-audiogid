package lv.bibliogid.manga.core.text

/** Визуальные двойники латиницы в кириллице (OCR путает их постоянно). */
object Homoglyphs {

    private val LAT_TO_CYR: Map<Char, Char> = mapOf(
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М',
        'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'X' to 'Х', 'Y' to 'У', 'S' to 'Ѕ',
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у',
        'u' to 'и', 'k' to 'к', 'n' to 'п', 'r' to 'г', 'B' to 'В'
    )

    /** Цифры/символы, которые OCR вставляет внутрь слов. */
    private val DIGIT_TO_CYR: Map<Char, Char> = mapOf(
        '0' to 'о', '3' to 'з', '4' to 'ч', '6' to 'б', '8' to 'в', '9' to 'д', '2' to 'з', '5' to 'с'
    )

    fun toCyrillic(c: Char): Char? = LAT_TO_CYR[c]

    fun digitToLetter(c: Char): Char? = DIGIT_TO_CYR[c]

    fun isArtifact(c: Char): Boolean =
        c.isDigit() || c in "$%^~|\\<>*_=+@#&/×÷≈∆∆∑∏√∞" || c in '\u0370'..'\u03FF' /* греческий */ ||
            c in '\u2190'..'\u21FF' || c in '\u2500'..'\u257F' /* псевдографика */

    /** Символы, которые нельзя выбрасывать никогда. */
    fun isKeptPunctuation(c: Char): Boolean =
        c in ".,!?…:;—–-'()[]«»„“”‚‘’/№"

    fun isLatinLetter(c: Char): Boolean = Chars.isLatin(c)
}
