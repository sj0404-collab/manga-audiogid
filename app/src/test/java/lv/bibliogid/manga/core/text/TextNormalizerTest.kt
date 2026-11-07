package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    companion object {
        @org.junit.BeforeClass
        @JvmStatic
        fun loadDict() {
            WordFrequencies.loadFromFile(java.io.File("src/main/assets/ru_freq.txt"))
        }
    }

    private val n = TextNormalizer()

    @Test
    fun `убирает мусорные символы и греческий алфавит`() {
        val out = n.normalizeLine("ПРИВЕТ λ МИР ∆ 12$ ^ ТЕБЕ")
        assertFalse(out.contains("λ"))
        assertFalse(out.contains("∆"))
        assertFalse(out.contains("$"))
        assertFalse(out.contains("^"))
        assertTrue(out.contains("ПРИВЕТ"))
        assertTrue(out.contains("МИР"))
    }

    @Test
    fun `латиница внутри русского слова превращается в кириллицу`() {
        val out = n.normalizeLine("ПPИВEТ MИP")
        assertEquals("ПРИВЕТ МИР", out)
        assertFalse(out.any { Homoglyphs.isLatinLetter(it) })
    }

    @Test
    fun `чисто латинские токены выбрасываются`() {
        val out = n.normalizeLine("ЭТО hello ТЕКСТ world")
        assertEquals("ЭТО ТЕКСТ", out)
    }

    @Test
    fun `чинит цифру внутри слова по словарю`() {
        val out = n.normalizeLine("П2ЖАЛИСТА помоги мне")
        assertTrue("получили: $out", out.lowercase().startsWith("пожалуйста"))
        assertFalse(out.any { it.isDigit() })
    }

    @Test
    fun `убирает знак больше из слова`() {
        val out = n.normalizeLine("П›алия поверь мне")
        assertFalse(out.contains("›"))
        assertTrue(out.contains("алия"))
    }

    @Test
    fun `восстанавливает utf8-как-cp1251`() {
        val broken = String("Привет мир".toByteArray(Charsets.UTF_8), charset("ISO-8859-1"))
        val out = n.normalizeLine(broken)
        assertEquals("Привет мир", Mojibake.fix(out).trim())
    }

    @Test
    fun `пробелы и пунктуация причёсаны`() {
        val out = n.normalizeLine("Привет ,   как  дела ?!")
        assertEquals("Привет, как дела?!", out)
    }

    @Test
    fun `пустая строка остаётся пустой`() {
        assertEquals("", n.normalizeLine("   "))
    }

    @Test
    fun `ничего не теряем из набора строк`() {
        val res = n.normalize(listOf(
            "ПРИВЕТ",
            "КАК ДЕЛА",
            "ВСЁ ХОРОШО",
            "ПОКА"
        ))
        assertTrue(res.report.outputReplicas >= 2)
        val joined = res.replicas.joinToString(" ")
        assertTrue(joined.contains("Привет"))
        assertTrue(joined.contains("Пока"))
        assertEquals(0, res.report.latinLeft)
    }

    @Test
    fun `реплика из латинских двойников не исчезает`() {
        // PaddleOCR часто отдаёт русский текст латинскими буквами — строка не должна пропасть
        val r = n.normalize(listOf("Kим MуBон!"))
        assertEquals(1, r.replicas.size)
        assertTrue(r.replicas[0].any { it in 'А'..'я' })
        assertTrue(r.replicas[0].length >= 6)
    }

    @Test
    fun `строка из смеси латиницы и кириллицы сохраняется целиком`() {
        val r = n.normalize(listOf("Kum Мувон!"))
        assertEquals(1, r.replicas.size)
        assertTrue(r.replicas[0].contains("увон", ignoreCase = true))
        assertEquals(0, r.replicas[0].count { Homoglyphs.isLatinLetter(it) })
    }

    @Test
    fun `английское слово посреди русского текста не превращается в мусор`() {
        val r = n.normalize(listOf("Он сказал OK и ушёл"))
        assertEquals(1, r.replicas.size)
        assertTrue(r.replicas[0].contains("ушёл", ignoreCase = true))
    }
}
