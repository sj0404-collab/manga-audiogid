package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Регион, в который слиплись два облачка, режется по разрыву между строками. */
class RegionSplitterTest {

    private fun region(lines: List<Pair<String, Box>>) = TextRegion(
        box = lines.drop(1).fold(lines.first().second) { acc, p -> acc.union(p.second) },
        type = RegionType.BUBBLE,
        rawLines = lines.map { it.first },
        lineBoxes = lines.map { it.second }
    )

    @Test
    fun `два облачка с пустотой между ними разделяются`() {
        // журнал 1.6.16, стр.20: «СТОП, ВСЁ НЕ ДОЛЖНО БЫТЬ ТАК ПРОСТО!» и
        // «ЭТО НЕ ЛЮБОВНЫЕ ОТНОШЕНИЯ!» — между ними ~600 px пустого облачка
        val r = region(
            listOf(
                "СТОП, ВСЁ" to Box(150f, 100f, 600f, 140f),
                "НЕ ДОЛЖНО БЫТЬ" to Box(140f, 146f, 610f, 186f),
                "ТАК ПРОСТО!" to Box(160f, 192f, 600f, 232f),
                "ЭТО НЕ ЛЮБОВНЫЕ" to Box(150f, 800f, 600f, 840f),
                "ОТНОШЕНИЯ!" to Box(180f, 846f, 570f, 886f)
            )
        )
        val parts = RegionSplitter.split(r)
        assertEquals(2, parts.size)
        assertEquals(3, parts[0].rawLines.size)
        assertEquals(2, parts[1].rawLines.size)
        assertTrue(parts[0].box.bottom < 300f)
        assertTrue(parts[1].box.top > 700f)
        // ни одна строка не потеряна
        assertEquals(5, parts.sumOf { it.rawLines.size })
    }

    @Test
    fun `плотное облачко не режется`() {
        val r = region(
            listOf(
                "ДА, Я СПОКОЙНА," to Box(150f, 100f, 600f, 140f),
                "РАЗ ТЕБЕ НРА-" to Box(150f, 146f, 600f, 186f),
                "ВИТСЯ…" to Box(150f, 192f, 600f, 232f)
            )
        )
        assertEquals(1, RegionSplitter.split(r).size)
    }

    @Test
    fun `рамка, раздутая в пустоту, подрезается по строкам`() {
        // 1.6.17, стр.20: бокс на 26 % высоты страницы, а текст — в его верхнем углу
        val r = TextRegion(
            box = Box(0f, 350f, 720f, 1150f),
            type = RegionType.BUBBLE,
            rawLines = listOf("СТОП, ВСЁ", "НЕ ДОЛЖНО БЫТЬ", "ТАК ПРОСТО!"),
            lineBoxes = listOf(
                Box(150f, 740f, 600f, 780f),
                Box(140f, 786f, 610f, 826f),
                Box(160f, 832f, 600f, 872f)
            )
        )
        val trimmed = RegionSplitter.trim(r)
        assertTrue("рамка не подрезана: ${trimmed.box}", trimmed.box.height < 300f)
        assertTrue(trimmed.box.top in 720f..740f)
        // строки и текст не тронуты
        assertEquals(3, trimmed.rawLines.size)
        assertEquals(3, trimmed.lineBoxes.size)
    }

    @Test
    fun `нормальная рамка не подрезается`() {
        val r = TextRegion(
            box = Box(140f, 730f, 620f, 890f),
            type = RegionType.BUBBLE,
            rawLines = listOf("СТОП, ВСЁ", "НЕ ДОЛЖНО БЫТЬ"),
            lineBoxes = listOf(
                Box(150f, 740f, 600f, 780f),
                Box(140f, 786f, 610f, 826f)
            )
        )
        assertEquals(r.box, RegionSplitter.trim(r).box)
    }
}
