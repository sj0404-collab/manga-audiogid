package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Полировка перед озвучкой: правила должны срабатывать и на тексте, пришедшем из кеша,
 * собранного прежней версией приложения. Строки взяты из аудиожурнала 1.6.12.
 */
class SpeechPolishTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val f = File("src/main/assets/ru_freq.txt")
            assertTrue("нет словаря ${f.absolutePath}", f.exists())
            WordFrequencies.loadFromFile(f)
            WordForms.ensureBuilt()
        }
    }

    private fun polish(s: String) = SpeechPolish.polish(s).text

    @Test
    fun `слипшееся слово разделяется`() {
        assertEquals(
            "Разве ты не будешь удивлен.",
            polish("Разве ты небудешь удивлен.")
        )
    }

    @Test
    fun `двойник шрифта исправляется`() {
        assertTrue(polish("Слушай. Я поаумала о количестве способов").contains("подумала"))
        assertTrue(polish("вытаскивал сераце дракона впервые").contains("сердце"))
    }

    @Test
    fun `приклеенная частица отделяется от имени`() {
        // регистр восстанавливает сборщик предложений, здесь важно само разделение
        assertTrue(polish("Ваше высочество. Может лианна.").lowercase().contains("ли анна"))
    }

    @Test
    fun `оторванный хвост приклеивается к глаголу`() {
        assertTrue(polish("При творите сь, что обдумываете").startsWith("Притворитесь"))
    }

    @Test
    fun `слово, разорванное переносом строки, склеивается`() {
        assertTrue(polish("Ты, наверное. Так гор. Дишься этим.").contains("гордишься"))
    }

    @Test
    fun `длинная склейка делится по словарю`() {
        assertTrue(polish("Давайте разберемся побыстрому.").contains("по быстрому"))
        assertEquals("Ты должна была сказать привет.", polish("Тыдолжнабыласказатьпривет."))
    }

    @Test
    fun `чистый текст не меняется`() {
        val clean = listOf(
            "Но я беспокоюсь. Ты уверена, что это сработает?",
            "Не позволяйте себе увлечься её высочеством.",
            "Да, это именно то, что вы должны делать.",
            "Мой план был таким безотлагательным, что я даже не задумалась о чувствах кая.",
            "Хотя это всего лишь контракт…"
        )
        for (c in clean) assertEquals("испортили: $c", c, polish(c))
    }

    @Test
    fun `правки перечисляются для журнала`() {
        val r = SpeechPolish.polish("вытаскивал сераце дракона")
        assertTrue(r.fixes.isNotEmpty())
        assertTrue(r.fixes.any { it.from.contains("сераце") && it.to.contains("сердце") })
    }

    // ------------------------------------- 1.6.15: пропущенная буква и цифры

    @Test
    fun `слово с пропущенной буквой восстанавливается`() {
        // журнал 1.6.14: «Вы должны быть равноушны» (буква «д» потеряна)
        assertTrue(polish("Вы должны быть равноушны.").contains("равнодушны"))
        assertTrue(polish("Вы должны быть равноаушны.").contains("равнодушны"))
    }

    @Test
    fun `цифры через дефис не превращаются в буквы`() {
        // «2-3 раза» читалось как «2-з раза»
        assertEquals("2-3 раза", WordSplitter.split("2-3 раза"))
        assertTrue(polish("она говорит 2-3 раза.").contains("2-3"))
        // текст, уже испорченный прошлой версией (или самим OCR): «2-з» -> «2-3»
        assertTrue(polish("она говорит 2-з раза.").contains("2-3"))
        val norm = TextNormalizer(joinMode = JoinMode.REGION)
            .normalize(listOf("При творите сь, что обаумываете что она говорит 2-3 раза."))
            .replicas.joinToString(" ")
        assertTrue("цифры испорчены: $norm", norm.contains("2-3") && !norm.contains("з-з"))
    }

    @Test
    fun `заглавная буква не теряется при починке`() {
        assertEquals("Сердце дракона", WordRepair.repairLine("Серце дракона"))
        assertEquals("Равнодушны ли они?", WordRepair.repairLine("Равноаушны ли они?"))
    }

    @Test
    fun `имена не переделываются в слова`() {
        for (n in listOf("Карсель", "Мувон", "Алисия", "Серин", "Риэль")) {
            assertEquals(n, WordRepair.repairLine(n))
        }
    }
}
