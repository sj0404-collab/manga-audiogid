package lv.bibliogid.manga.core.ocr

/**
 * Группировка строк OCR в регионы (облачка/плашки).
 * Строки склеиваются, если они рядом по вертикали, пересекаются по горизонтали
 * и расстояние между ними меньше 1.2 высоты строки.
 */
object RegionGrouper {

    fun group(lines: List<OcrLine>, maxGapFactor: Float = 2.0f): List<Pair<Box, List<OcrLine>>> {
        if (lines.isEmpty()) return emptyList()
        val remaining = lines.sortedBy { it.box.top }.toMutableList()
        val groups = ArrayList<MutableList<OcrLine>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val group = mutableListOf(seed)
            var changed = true
            while (changed) {
                changed = false
                val current = bounds(group)
                val it = remaining.iterator()
                while (it.hasNext()) {
                    val cand = it.next()
                    if (belongs(current, group, cand, maxGapFactor)) {
                        group.add(cand)
                        it.remove()
                        changed = true
                    }
                }
            }
            groups.add(group)
        }
        // Регион не должен перешагивать пустоту: в журнале 1.6.16 облачко «СТОП, ВСЁ НЕ ДОЛЖНО
        // БЫТЬ ТАК ПРОСТО!» слиплось с «ЭТО НЕ ЛЮБОВНЫЕ ОТНОШЕНИЯ!» (600 px пустого облачка
        // между ними) — получилась одна реплика на 38 % высоты страницы, и последние строки
        // первой фразы не дочитывались.
        return groups
            .flatMap { splitBigGaps(it) }
            .map { g -> bounds(g) to g.sortedBy { it.box.top } }
    }

    /** Разрыв, после которого строки — точно другое облачко (доли высоты строки). */
    private const val SPLIT_GAP = 2.2f

    /**
     * Режет группу на части по большим вертикальным разрывам.
     * Внутри одного облачка межстрочный интервал меньше высоты строки, поэтому
     * всё, что дальше [SPLIT_GAP], — уже другое облачко (даже если группировщик
     * склеил их из-за «съехавшей» высоты строки).
     */
    private fun splitBigGaps(group: List<OcrLine>): List<List<OcrLine>> {
        if (group.size < 2) return listOf(group)
        val sorted = group.sortedBy { it.box.top }
        val heights = sorted.map { it.box.height }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(4f)
        val maxGap = median * SPLIT_GAP
        val parts = ArrayList<MutableList<OcrLine>>()
        var cur: MutableList<OcrLine>? = null
        var bottom = 0f
        for (line in sorted) {
            // только после законченной фразы: иначе разрез проходит внутри облачка
            val prev = cur?.lastOrNull()
            val prevEnds = prev != null && prev.text.trimEnd().let {
                it.isNotEmpty() && it.last() in ".!?…»\""
            }
            if (cur != null && prevEnds && line.box.top - bottom > maxGap) cur = null
            if (cur == null) {
                cur = mutableListOf()
                parts.add(cur)
                bottom = line.box.bottom
            }
            cur.add(line)
            if (line.box.bottom > bottom) bottom = line.box.bottom
        }
        return parts
    }

    private fun bounds(group: List<OcrLine>): Box =
        group.map { it.box }.reduce { a, b -> a.union(b) }

    private fun belongs(current: Box, group: List<OcrLine>, cand: OcrLine, gapFactor: Float): Boolean {
        val g = cand.box
        val hOverlap = current.horizontalOverlap(g)
        val minWidth = minOf(current.width, g.width).coerceAtLeast(1f)
        val horizontallyClose = hOverlap >= minWidth * 0.35f ||
            (g.left <= current.right + minWidth * 0.6f && g.right >= current.left - minWidth * 0.6f)
        if (!horizontallyClose) return false
        // строки одного облачка держатся общей вертикальной оси;
        // если центры далеко разъехались — это соседнее облачко, а не продолжение
        val maxWidth = maxOf(current.width, g.width).coerceAtLeast(1f)
        if (kotlin.math.abs(current.centerX - g.centerX) > maxWidth * 0.55f) return false

        val lineHeight = group.map { it.box.height }.average().toFloat().coerceAtLeast(4f)
        val maxGap = lineHeight * gapFactor
        val verticalGap = when {
            g.top >= current.bottom -> g.top - current.bottom
            g.bottom <= current.top -> current.top - g.bottom
            else -> 0f
        }
        if (verticalGap > maxGap) return false
        // не склеиваем два явно разных облачка: суммарная высота не должна прыгать
        val union = current.union(g)
        return union.height <= lineHeight * 12f
    }
}
