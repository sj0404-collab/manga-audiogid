package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineProjectorTest {

    private fun maskOf(w: Int, h: Int, blocks: List<IntArray>): BooleanArray {
        val m = BooleanArray(w * h)
        for (b in blocks) {
            for (y in b[1] until b[3]) for (x in b[0] until b[2]) m[y * w + x] = true
        }
        return m
    }

    @Test
    fun `две строки текста дают две рамки`() {
        val mask = maskOf(100, 60, listOf(
            intArrayOf(10, 5, 60, 15),
            intArrayOf(10, 35, 60, 45)
        ))
        val boxes = LineProjector.project(mask, 100, 60, thickenX = 0)
        assertEquals(2, boxes.size)
        assertEquals(10f, boxes[0].left)
        assertEquals(60f, boxes[0].right)
    }

    @Test
    fun `буквы внутри строки склеиваются в одну рамку`() {
        // «П Р И В Е Т» — отдельные буквы с пробелами
        val letters = (0 until 6).map { i -> intArrayOf(10 + i * 12, 10, 10 + i * 12 + 8, 22) }
        val mask = maskOf(120, 40, letters)
        val boxes = LineProjector.project(mask, 120, 40, thickenX = 2)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].width > 70f)
    }

    @Test
    fun `два облачка в одной строке разъезжаются по разным рамкам`() {
        val mask = maskOf(300, 60, listOf(
            intArrayOf(10, 20, 60, 34),
            intArrayOf(220, 20, 270, 34)
        ))
        val boxes = LineProjector.project(mask, 300, 60, thickenX = 0)
        assertEquals(2, boxes.size)
        assertTrue(boxes[1].left > boxes[0].right)
    }

    @Test
    fun `пустая маска — ни одной рамки`() {
        assertEquals(0, LineProjector.project(BooleanArray(100), 10, 10).size)
    }
}
