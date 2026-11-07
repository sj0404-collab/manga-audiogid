package lv.bibliogid.manga.core.ocr

/**
 * Режет регион, в который слиплись ДВА облачка.
 *
 * Журнал 1.6.16, страница 20/20: облачко «СТОП, ВСЁ НЕ ДОЛЖНО БЫТЬ ТАК ПРОСТО!» и
 * «ЭТО НЕ ЛЮБОВНЫЕ ОТНОШЕНИЯ!» попали в один регион — между ними ~600 px пустого
 * облачка. Итог: одна реплика высотой 38 % страницы, чужие фразы звучат слитно,
 * а последние строки первой фразы не дочитывались.
 *
 * Внутри одного облачка строки стоят плотно (зазор меньше высоты строки), поэтому
 * всё, что дальше [SPLIT_GAP] средней высоты строки, — уже другое облачко.
 * Решение принимается по РАМКАМ СТРОК (lineBoxes), то есть по реальной геометрии,
 * а не по тексту — поэтому работает независимо от того, как регион слипся.
 */
object RegionSplitter {

    /** Зазор между строками, после которого это точно другое облачко. */
    private const val SPLIT_GAP = 1.6f

    /**
     * Обрезает рамку региона до РАМОК СТРОК, если она раздулась в пустоту.
     *
     * Журнал 1.6.17, стр.20: у облачка «СТОП, ВСЁ НЕ ДОЛЖНО БЫТЬ ТАК ПРОСТО!» рамка
     * выросла на 26 % высоты страницы — детектор отдал бокс вместе с пустым облачком-«ванной»,
     * и текст оказался в крошечном углу огромной рамки. Площадь рамки больше площади строк
     * в 2,5 раза — значит она захватила пустоту, подрезаем.
     */
    fun trim(region: TextRegion): TextRegion {
        val boxes = region.lineBoxes
        if (boxes.isEmpty()) return region
        val linesBox = boxes.drop(1).fold(boxes.first()) { acc, b -> acc.union(b) }
        if (linesBox.width <= 1f || linesBox.height <= 1f) return region
        val ratio = region.box.area / linesBox.area
        val tooTall = region.box.height > linesBox.height * 2.5f
        if (ratio <= 2.5f && !tooTall) return region
        val padX = (linesBox.width * 0.08f).coerceAtLeast(2f)
        val padY = (linesBox.height * 0.12f).coerceAtLeast(2f)
        return region.copy(
            box = Box(
                linesBox.left - padX, linesBox.top - padY,
                linesBox.right + padX, linesBox.bottom + padY
            )
        )
    }

    fun split(region: TextRegion): List<TextRegion> {
        val lines = region.rawLines
        val boxes = region.lineBoxes
        if (lines.size < 2 || boxes.size != lines.size) return listOf(region)
        val pairs = lines.indices
            .map { lines[it] to boxes[it] }
            .sortedBy { it.second.top }
        val heights = pairs.map { it.second.height }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(4f)
        val maxGap = median * SPLIT_GAP

        val parts = ArrayList<MutableList<Pair<String, Box>>>()
        var cur: MutableList<Pair<String, Box>>? = null
        var bottom = 0f
        for (p in pairs) {
            if (cur != null && p.second.top - bottom > maxGap) cur = null
            if (cur == null) {
                cur = mutableListOf()
                parts.add(cur)
                bottom = p.second.bottom
            }
            cur.add(p)
            if (p.second.bottom > bottom) bottom = p.second.bottom
        }
        if (parts.size < 2) return listOf(region)
        return parts.map { part ->
            val box = part.drop(1).fold(part.first().second) { acc, p -> acc.union(p.second) }
            TextRegion(
                box = box,
                type = region.type,
                rawLines = part.map { it.first },
                metrics = region.metrics,
                lineBoxes = part.map { it.second }
            )
        }
    }
}
