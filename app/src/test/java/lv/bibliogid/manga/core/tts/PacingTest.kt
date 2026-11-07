package lv.bibliogid.manga.core.tts

import lv.bibliogid.manga.core.ocr.Box
import lv.bibliogid.manga.core.ocr.RegionType
import lv.bibliogid.manga.core.ocr.Replica
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingTest {

    companion object {
        @org.junit.BeforeClass
        @JvmStatic
        fun loadDict() {
            val f = java.io.File("src/main/assets/ru_freq.txt")
            if (f.exists()) lv.bibliogid.manga.core.text.WordFrequencies.loadFromFile(f)
        }
    }

    private fun replica(text: String, i: Int = 0) = Replica(i, text, RegionType.BUBBLE, Box(0f, 0f, 1f, 1f))

    @Test
    fun `скорость ограничена 0_25 - 2_0`() {
        assertEquals(0.25f, Pacing.clampSpeed(0.01f), 0.0001f)
        assertEquals(2.0f, Pacing.clampSpeed(9f), 0.0001f)
    }

    @Test
    fun `медленная скорость удлиняет реплику`() {
        val fast = Pacing.replicaDurationMs(60, 2.0f)
        val slow = Pacing.replicaDurationMs(60, 0.25f)
        assertTrue(slow > fast * 4)
    }

    @Test
    fun `страница без текста не пролетает мгновенно`() {
        val d = Pacing.emptyPageDwellMs(1.0f)
        assertTrue("d=$d", d >= 200)
        val at1x = Pacing.emptyPageDwellMs(1.0f)
        val at05x = Pacing.emptyPageDwellMs(0.5f)
        assertTrue("at1x=$at1x at05x=$at05x", kotlin.math.abs(at05x - at1x * 2) <= 250)
    }

    @Test
    fun `пустой список реплик даёт положительную длительность`() {
        assertTrue(Pacing.pageDurationMs(emptyList(), 2.0f) > 100)
    }

    @Test
    fun `длительность страницы складывается из реплик`() {
        val reps = listOf(replica("Привет", 0), replica("Как дела", 1))
        val total = Pacing.pageDurationMs(reps, 1.0f)
        assertTrue(total >= Pacing.replicaDurationMs(6, 1f) + Pacing.replicaDurationMs(8, 1f))
    }

    @Test
    fun `сценарий TTS сохраняет порядок и количество`() {
        val reps = listOf(replica("Первая", 0), replica("Вторая", 1), replica("  ", 2))
        val script = TtsScript.build(reps, 1.0f)
        assertEquals(2, script.size)
        assertEquals(0, script[0].replicaIndex)
        assertEquals(1, script[1].replicaIndex)
    }

    @Test
    fun `длинный текст дробится`() {
        val long = "Привет. ".repeat(600)
        val parts = TtsScript.splitLong(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 3200 })
    }

    /** Журнал 1.6.9: «стр.5 реплика 4: Еу.», «стр.7 реплика 2: Сто.» — обрывки звучали голосом. */
    @Test
    fun `короткие обрывки не попадают в сценарий озвучки`() {
        val rep = listOf(
            replica("Еу.", 0), replica("Хмм.", 1), replica("Да.", 2), replica("Мусор.", 3)
        )
        val script = TtsScript.build(rep, 1.0f)
        val texts = script.map { it.text }
        assertTrue("«Еу.» не должен озвучиваться", texts.none { it.startsWith("Еу") })
        assertTrue("«Да.» — настоящая реплика", texts.any { it.startsWith("Да") })
        assertTrue("«Мусор.» — настоящая реплика", texts.any { it.startsWith("Мусор") })
    }

    @Test
    fun `длинные реплики не считаются обрывками`() {
        assertFalse(TtsScript.isFiller("Стоп. Всё не должно быть так."))
        assertFalse(TtsScript.isFiller("Разве?"))
    }

    @Test
    fun `слово, разорванное границей реплик, склеивается`() {
        // журнал 1.6.15: «Так гор.» + «Дишься этим.» звучало как «так гор… дишься»
        val out = TtsScript.build(
            listOf(replica("Так гор.", 0), replica("Дишься этим.", 1)),
            1.0f
        )
        assertEquals(1, out.size)
        assertEquals("Так гордишься этим.", out[0].text)
    }

    @Test
    fun `разные предложения не склеиваются`() {
        val out = TtsScript.build(
            listOf(replica("Он ушёл.", 0), replica("Дишься этим.", 1), replica("Да.", 2)),
            1.0f
        )
        assertTrue(out.size >= 2)
        assertTrue(out.none { it.text.contains("ушёлдишься") })
    }
}
