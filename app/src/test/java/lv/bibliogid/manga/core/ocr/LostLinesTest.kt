package lv.bibliogid.manga.core.ocr

import lv.bibliogid.manga.core.text.WordFrequencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Страховка «ни одна строка не пропала»: пропавшие строки возвращаются в реплику. */
class LostLinesTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt"))
        }
    }

    @Test
    fun `строка, не попавшая в текст, находится`() {
        val raw = listOf("СТОП, ВСЁ", "НЕ ДОЛЖНО БЫТЬ", "ТАК ПРОСТО!")
        // реплика собралась без последней строки — ровно то, на что жаловался журнал
        val lost = LostLines.findLost("Стоп, всё не должно быть.", raw)
        assertEquals(listOf("ТАК ПРОСТО!"), lost)
    }

    @Test
    fun `когда все строки на месте — ничего не дописываем`() {
        val raw = listOf("СТОП, ВСЁ", "НЕ ДОЛЖНО БЫТЬ", "ТАК ПРОСТО!")
        assertTrue(LostLines.findLost("Стоп, всё не должно быть так просто!", raw).isEmpty())
    }

    @Test
    fun `мусорная строка не возвращается`() {
        val raw = listOf("СТОП, ВСЁ", "QQQ ZZZ WWW")
        assertTrue(LostLines.findLost("Стоп, всё.", raw).isEmpty())
    }
}
