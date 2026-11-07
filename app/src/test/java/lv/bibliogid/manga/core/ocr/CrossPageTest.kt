package lv.bibliogid.manga.core.ocr

import lv.bibliogid.manga.core.text.WordFrequencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Склейка реплики, разрезанной границей страниц (жалоба: «Но разве.» → стр.3). */
class CrossPageTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt"))
        }
    }

    private fun replica(text: String, open: Boolean = false, silence: Boolean = false) = Replica(
        index = 0,
        text = text,
        type = RegionType.BUBBLE,
        box = Box(0f, 0f, 100f, 100f),
        silence = silence,
        openEnded = open
    )

    @Test
    fun `незаконченная фраза склеивается с началом следующей страницы`() {
        val prev = replica("Но разве.", open = true)
        val next = replica("Наш лидер уже недостаточно плох?")
        assertTrue(CrossPage.canJoin(prev, next))
        assertEquals("Но разве наш лидер уже недостаточно плох?", CrossPage.join(prev.text, next.text))
    }

    @Test
    fun `законченная фраза не склеивается`() {
        assertFalse(CrossPage.canJoin(replica("Прочь."), replica("Да, это верно.")))
    }

    @Test
    fun `длинный хвост не склеиваем`() {
        val long = "Это очень длинная реплика без точки в конце которая занимает почти всю страницу и явно закончена"
        assertTrue(long.count { it.isLetter() } > CrossPage.MAX_OPEN_LETTERS)
        assertFalse(CrossPage.canJoin(replica(long, open = true), replica("Дальше.")))
    }

    @Test
    fun `молчание и обрывки не участвуют в склейке`() {
        assertFalse(CrossPage.canJoin(replica("…", open = true, silence = true), replica("Текст.")))
        assertFalse(CrossPage.canJoin(replica("Разве.", open = true), replica("…", silence = true)))
        assertFalse(CrossPage.canJoin(null, replica("Текст.")))
    }

    @Test
    fun `обычное слово переходит в строчную`() {
        assertEquals(
            "Но разве наш лидер уже недостаточно плох?",
            CrossPage.join("Но разве", "Наш лидер уже недостаточно плох?")
        )
    }

    @Test
    fun `имя с заглавной остаётся с заглавной`() {
        // «кай» — редкое/незнакомое слово, значит это имя: строчную букву не ставим
        assertTrue(CrossPage.join("Разве", "Кай пришёл?").contains("Кай"))
        assertTrue(CrossPage.join("Разве", "Кимувон сказал.").contains("Кимувон"))
    }

    @Test
    fun `точка в конце убирается`() {
        assertEquals("Но разве это так?", CrossPage.join("Но разве.", "это так?"))
    }
}
