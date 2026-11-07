package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Слово, разорванное переводом строки, не должно звучать дважды. */
class LineStitcherTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt"))
        }
    }

    @Test
    fun `обрыв имени склеивается`() {
        val out = LineStitcher.stitch(listOf("Я БЛИЗКА С КИМ МУВО", "НОМ ТО ОНИ НАЧНУТ"))
        assertEquals("Я БЛИЗКА С КИМ МУВОНОМ ТО ОНИ НАЧНУТ", out.joinToString(" "))
    }

    @Test
    fun `после точки не склеиваем`() {
        val out = LineStitcher.stitch(listOf("ПРИВЕТ.", "БЕЛЫЙ ДОМ"))
        assertEquals(2, out.size)
    }

    @Test
    fun `нормальные слова рядом остаются раздельными`() {
        val out = LineStitcher.stitch(listOf("ОН СКАЗАЛ", "ЕМУ ОБИДНО"))
        assertEquals(2, out.size)
    }
}
