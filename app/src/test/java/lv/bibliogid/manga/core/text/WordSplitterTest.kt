package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Проверка восстановления пробелов на РЕАЛЬНЫХ строках PP-OCRv3 (то, что видел пользователь)
 * и на «слипшихся» строках без пробелов.
 */
class WordSplitterTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val f = File("src/main/assets/ru_freq.txt")
            assertTrue("нет словаря ${f.absolutePath}", f.exists())
            WordFrequencies.loadFromFile(f)
        }
    }

    @Test
    fun `слипшаяся строка делится на слова`() {
        assertEquals("ты должна была сказать привет", WordSplitter.splitCompact("тыдолжнабыласказатьпривет"))
    }

    @Test
    fun `латинские двойники превращаются в русские слова`() {
        val out = WordSplitter.split("TыдOлЖHA6ылACKA3AтьпPивET")
        assertTrue(out.contains("должна"))
        assertTrue(out.contains("привет"))
        assertFalse(out.any { it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test
    fun `почему прячешься читается сразу`() {
        val out = WordSplitter.split("пOчEMyпPячEшьCя?")
        assertEquals("почему прячешься?", out)
    }

    @Test
    fun `текст с нормальными пробелами не трогаем`() {
        val line = "Ты должна была сказать привет"
        assertFalse(WordSplitter.needsSplit(line))
    }

    @Test
    fun `кириллизация цифр и латиницы`() {
        assertEquals("была сказать", WordSplitter.split("6ыла ckазать"))
    }

    @Test
    fun `словарь загружен и не пустой`() {
        assertTrue(WordFrequencies.isLoaded)
        assertTrue(WordFrequencies.size > 40_000)
        assertTrue(WordFrequencies.contains("привет"))
    }

    @Test
    fun `слипшиеся два слова разделяются пробелом`() {
        // реальный случай из панели «Текст страницы 2»: ТЫДОЛЖНА
        assertEquals("ты должна была сказать привет", WordSplitter.split("тыдолжна была сказать привет"))
        assertEquals("это двое", WordSplitter.split("этодвое"))
        assertEquals("не могу", WordSplitter.split("немогу"))
    }

    @Test
    fun `нормальные слова не разрезаются`() {
        assertEquals("настолько близки", WordSplitter.split("настолько близки"))
        assertEquals("привет как дела", WordSplitter.split("привет как дела"))
        assertEquals("ха-ха-ха", WordSplitter.split("ха-ха-ха"))
    }
}
