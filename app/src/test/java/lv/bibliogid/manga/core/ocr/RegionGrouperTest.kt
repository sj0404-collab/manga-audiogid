package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionGrouperTest {

    @Test
    fun `строки одного облачка собираются в один регион`() {
        val lines = listOf(
            OcrLine("ВЫ ДОЛЖНЫ БЫТЬ", Box(100f, 100f, 300f, 120f)),
            OcrLine("РАВНОДУШНЫ", Box(102f, 124f, 296f, 144f)),
            OcrLine("ПРИТВОРИТЕСЬ", Box(101f, 148f, 302f, 168f))
        )
        val groups = RegionGrouper.group(lines)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].second.size)
    }

    @Test
    fun `два далёких облачка не склеиваются`() {
        val lines = listOf(
            OcrLine("ПЕРВОЕ", Box(100f, 100f, 300f, 120f)),
            OcrLine("ВТОРОЕ", Box(100f, 400f, 300f, 420f))
        )
        val groups = RegionGrouper.group(lines)
        assertEquals(2, groups.size)
    }

    @Test
    fun `склеивание не теряет строки`() {
        val lines = (0..9).map { i ->
            OcrLine("СТРОКА $i", Box(100f, 100f + i * 22f, 300f, 118f + i * 22f))
        }
        val groups = RegionGrouper.group(lines)
        val total = groups.sumOf { it.second.size }
        assertEquals(10, total)
        assertTrue(groups.size >= 1)
    }
}
