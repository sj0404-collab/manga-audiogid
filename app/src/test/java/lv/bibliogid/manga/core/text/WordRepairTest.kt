package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** «Двойники» шрифта: Д, нарисованное как А. */
class WordRepairTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val f = File("src/main/assets/ru_freq.txt")
            WordFrequencies.loadFromFile(f)
        }
    }

    @Test
    fun `АЕЛО читается как дело`() {
        assertEquals("дело", WordRepair.repairWord("АЕЛО"))
    }

    @Test
    fun `слово без пары в словаре остаётся как есть`() {
        // «тренде» в частотном словаре нет — не гадаем и не портим
        assertNull(WordRepair.repairWord("ТРЕНАЕ"))
    }

    @Test
    fun `нормальные слова не трогаются`() {
        assertNull(WordRepair.repairWord("дело"))
        assertNull(WordRepair.repairWord("тренде"))
        assertNull(WordRepair.repairWord("привет"))
    }

    @Test
    fun `имя без словарной пары остаётся как есть`() {
        assertNull(WordRepair.repairWord("мувон"))
        assertNull(WordRepair.repairWord("ким"))
    }

    @Test
    fun `пунктуация сохраняется на месте`() {
        assertEquals("дело,", WordRepair.repairToken("АЕЛО,"))
    }
}
