package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Подготовка текста для TTS: имена капсом не должны читаться по буквам. */
class SpokenTextTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt"))
        }
    }

    @Test
    fun `имя капсом переводится в обычный регистр`() {
        assertEquals("Мувон", SpokenText.prepare("МУВОН"))
        assertEquals("КИМ Мувон не тяни", SpokenText.prepare("КИМ МУВОН не тяни"))
    }

    @Test
    fun `обычные слова не портятся`() {
        assertEquals("Не тяни меня за худи.", SpokenText.prepare("Не тяни меня за худи."))
    }

    @Test
    fun `междометия капсом не читаются по буквам`() {
        // журнал 1.6.15: «ХММ» звучало как «ха-эм-эм»
        assertEquals("Хм", SpokenText.prepare("ХММ"))
        assertEquals("Хм", SpokenText.prepare("ХМММ"))
        assertEquals("Хм", SpokenText.prepare("Хм-м-м"))
        assertEquals("Мм", SpokenText.prepare("Ммм"))
        assertEquals("Мм, вкусно?", SpokenText.prepare("МММ, вкусно?"))
        assertEquals("Ха-ха", SpokenText.prepare("ХА-ХА"))
    }

    @Test
    fun `междометия не задевают обычные слова`() {
        for (w in listOf("Мама", "Мама пришла.", "Ум", "Ума не хватает.", "Это моя мама")) {
            assertEquals(w, SpokenText.prepare(w))
        }
    }

    @Test
    fun `одиночные буквы склеиваются`() {
        val out = SpokenText.prepare("его зовут М У В О Н")
        assertFalse(out.contains("М У В О Н"))
    }
}
