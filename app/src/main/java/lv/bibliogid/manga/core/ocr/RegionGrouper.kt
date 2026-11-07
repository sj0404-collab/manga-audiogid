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
        return groups.map { g -> bounds(g) to g.sortedBy { it.box.top } }
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
