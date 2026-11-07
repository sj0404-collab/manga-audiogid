package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionClassifierTest {

    @Test
    fun `белое облачко с чёрным текстом — BUBBLE`() {
        val t = RegionClassifier.classify(
            RegionMetrics(insideLuma = 40.0, ringLuma = 235.0, inkDensity = 0.2,
                ringEdgeDensity = 0.15, glyphHeightPx = 18f, regionWidthPx = 200f, chars = 30)
        )
        assertEquals(RegionType.BUBBLE, t)
    }

    @Test
    fun `рваный контур — THOUGHT`() {
        val t = RegionClassifier.classify(
            RegionMetrics(insideLuma = 40.0, ringLuma = 230.0, inkDensity = 0.2,
                ringEdgeDensity = 0.45, glyphHeightPx = 18f, regionWidthPx = 200f, chars = 30)
        )
        assertEquals(RegionType.THOUGHT, t)
    }

    @Test
    fun `крупные глифы и мало букв — SFX`() {
        val t = RegionClassifier.classify(
            RegionMetrics(insideLuma = 20.0, ringLuma = 120.0, inkDensity = 0.3,
                ringEdgeDensity = 0.3, glyphHeightPx = 60f, regionWidthPx = 300f, chars = 5)
        )
        assertEquals(RegionType.SFX, t)
    }

    @Test
    fun `светлый текст на тёмном рисунке — FREE_TEXT`() {
        val t = RegionClassifier.classify(
            RegionMetrics(insideLuma = 230.0, ringLuma = 60.0, inkDensity = 0.2,
                ringEdgeDensity = 0.7, glyphHeightPx = 20f, regionWidthPx = 250f, chars = 40)
        )
        assertEquals(RegionType.FREE_TEXT, t)
    }

    @Test
    fun `широкая серая плашка — CAPTION`() {
        val t = RegionClassifier.classify(
            RegionMetrics(insideLuma = 60.0, ringLuma = 190.0, inkDensity = 0.2,
                ringEdgeDensity = 0.2, glyphHeightPx = 16f, regionWidthPx = 700f, chars = 60,
                columnFillRatio = 0.9)
        )
        assertEquals(RegionType.CAPTION, t)
    }
}
