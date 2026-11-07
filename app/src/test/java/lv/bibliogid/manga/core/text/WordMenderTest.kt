package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class WordMenderTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt"))
        }
    }

    @Test
    fun `разорванное слово склеивается`() {
        assertEquals("когда увидела меня.", WordMender.mend("КОГДА УВИДЕЛА МЕН я.").lowercase())
    }

    @Test
    fun `слово, разбитое на куски, собирается целиком`() {
        val out = WordMender.mend("Почему пр яч ешь с я?").lowercase()
        assertEquals("почему прячешься?", out)
    }

    @Test
    fun `кривой регистр внутри слова исправляется`() {
        assertEquals("Ты должна была СКАЗАТЬ ПРИВЕТ.",
            WordMender.mend("Ты должна бЫЛА СКАЗАТЬ ПРИВЕТ."))
    }

    @Test
    fun `КАПС приводится к обычному тексту нормализатором`() {
        val n = TextNormalizer(joinMode = JoinMode.REGION)
        val r = n.normalize(listOf("Ты должна бЫЛА СКАЗАТЬ ПРИВЕТ."))
        assertEquals("Ты должна была сказать привет.", r.replicas[0])
    }

    @Test
    fun `имена не склеиваются и не портятся`() {
        val out = WordMender.mend("Ким Мувон!")
        assertTrue(out.contains("Ким"))
        assertTrue(out.contains("Мувон"))
    }

    @Test
    fun `нормальный текст не меняется`() {
        val s = "Ты должна была сказать привет, когда увидела меня."
        assertEquals(s, WordMender.mend(s))
    }
}
