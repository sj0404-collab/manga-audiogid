package lv.bibliogid.manga.core.ocr

import lv.bibliogid.manga.core.text.JoinMode
import lv.bibliogid.manga.core.text.TextNormalizer
import lv.bibliogid.manga.core.text.WordFrequencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Тексты, которые PP-OCRv5 реально отдал с ваших страниц (скриншоты 06.11.2026),
 * прогнаны через сборку в «как читается». Проверяем, что в панели ровный русский текст.
 */
class RealPageTextTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt"))
        }
    }

    private val builder = PageTextBuilder(TextNormalizer(joinMode = JoinMode.REGION))

    private fun text(vararg lines: String): String {
        val region = TextRegion(Box(0f, 0f, 300f, 200f), RegionType.BUBBLE, lines.toList())
        val page = builder.build(listOf(region), ReadingMode.MANGA_RTL, "paddle", 10)
        return page.replicas.joinToString(" ") { it.text }
    }

    @Test
    fun `реплика с кривым регистром читается ровно`() {
        assertEquals("Ты должна была сказать привет.",
            text("Ты должна бЫЛА СКАЗАТЬ ПРИВЕТ."))
    }

    @Test
    fun `разорванное слово и перенос через дефис собираются`() {
        assertEquals("Когда увидела меня. Почему прячешься?",
            text("КОГДА УВИДЕЛА МЕН я. ПОЧЕМУ ПРЯ-", "ЧЕШЬСЯ?"))
    }

    @Test
    fun `смех и крик остаются читаемыми`() {
        val shout = text("AAAA! НЕ ТЯНИ.")
        assertTrue(shout.contains("не тяни", ignoreCase = true))
        val laugh = text("Ха- ха- ха.")
        assertTrue(laugh.contains("ха-ха-ха", ignoreCase = true) || laugh.contains("ха - ха", ignoreCase = true))
    }

    @Test
    fun `в готовом тексте нет латиницы и мусора`() {
        val t = text("КОГДА УВИДЕЛА МЕН я. ПОЧЕМУ ПРЯ-", "ЧЕШЬСЯ?", "ЗА ХУДИ. РАСТЯ-", "НЕШЬ ВЕДЬ!")
        assertFalse(t.any { it in 'a'..'z' || it in 'A'..'Z' })
        assertFalse(t.contains("  "))
        assertTrue(t.contains("прячешься", ignoreCase = true))
    }
}
