package lv.bibliogid.manga.core.ocr

import lv.bibliogid.manga.core.tts.TtsScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Молчание» — облачко, в котором только многоточие.
 *
 * Из журнала 20:24: такие облачка находились детектором, но из текста исчезали
 * (нормализатор вычищал точки), поэтому в ленте оставалась пустая рамка, и
 * последовательность реплик выглядела так, будто страница перепрыгнула облачко.
 */
class SilenceTest {

    @Test
    fun `многоточие распознаётся как молчание`() {
        assertTrue(Silence.isSilence("…"))
        assertTrue(Silence.isSilence("..."))
        assertTrue(Silence.isSilence(". . ."))
        assertTrue(Silence.isSilence("… …"))
        assertTrue(Silence.isSilence("- … -"))
    }

    @Test
    fun `текст и цифры молчанием не считаются`() {
        assertFalse(Silence.isSilence("Ты был?"))
        assertFalse(Silence.isSilence("48"))
        assertFalse(Silence.isSilence("T. 48"))
        assertFalse(Silence.isSilence("!"))
        assertFalse(Silence.isSilence(""))
    }

    @Test
    fun `многоточие в сырых строках движка тоже ловится`() {
        assertTrue(Silence.inRaw(listOf("…")))
        assertTrue(Silence.inRaw(listOf("...", "")))
        assertFalse(Silence.inRaw(listOf("Он думал...")))
        assertFalse(Silence.inRaw(listOf("T. 48")))
    }

    @Test
    fun `облачко с многоточием остаётся репликой, но не озвучивается`() {
        val builder = PageTextBuilder()
        val regions = listOf(
            TextRegion(Box(20f, 100f, 220f, 180f), RegionType.BUBBLE, listOf("…")),
            TextRegion(Box(300f, 400f, 520f, 480f), RegionType.BUBBLE, listOf("ГДЕ ТЫ БЫЛ?"))
        )
        val page = builder.build(regions, ReadingMode.WEBTOON_VERTICAL, "test", 1)
        assertEquals(2, page.replicas.size)
        assertEquals(1, page.silenceCount)
        val silent = page.replicas.first { it.silence }
        assertEquals("…", silent.text)
        // «молчание» видно на странице рамкой, но голосом не читается
        val script = TtsScript.build(page.replicas, 1.0f)
        assertEquals(1, script.size)
        assertTrue(script[0].text.contains("ГДЕ", ignoreCase = true))
    }
}
