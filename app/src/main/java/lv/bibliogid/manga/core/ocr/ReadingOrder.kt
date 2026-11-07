package lv.bibliogid.manga.core.ocr

enum class ReadingMode {
    /** Манга: справа налево, сверху вниз. */
    MANGA_RTL,
    /** Комикс/еврокомикс: слева направо. */
    COMIC_LTR,
    /** Вебтун: строго сверху вниз. */
    WEBTOON_VERTICAL
}

/**
 * Порядок чтения регионов. Работает по «полосам» (bands): регионы, перекрывающиеся
 * по вертикали более чем наполовину, считаются одной строкой и упорядочиваются по X
 * (справа налево для манги, слева направо для комиксов). Вебтун — чистый Y-порядок.
 */
object ReadingOrder {

    fun order(regions: List<TextRegion>, mode: ReadingMode): List<TextRegion> {
        if (regions.size <= 1) return regions
        return when (mode) {
            ReadingMode.WEBTOON_VERTICAL -> regions.sortedWith(
                compareBy<TextRegion>({ it.box.top }, { it.box.left })
            )
            else -> sortHorizontally(regions, mode == ReadingMode.MANGA_RTL)
        }
    }

    private fun sortHorizontally(regions: List<TextRegion>, rightToLeft: Boolean): List<TextRegion> {
        val sorted = regions.sortedBy { it.box.top }
        val bands = ArrayList<MutableList<TextRegion>>()
        for (r in sorted) {
            var placed = false
            for (band in bands) {
                val ref = band[0]
                val overlap = ref.box.verticalOverlap(r.box)
                val minH = minOf(ref.box.height, r.box.height).coerceAtLeast(1f)
                if (overlap >= minH * 0.5f) {
                    band.add(r)
                    placed = true
                    break
                }
            }
            if (!placed) bands.add(mutableListOf(r))
        }
        val cmp = if (rightToLeft) {
            compareByDescending<TextRegion> { it.box.right }
        } else {
            compareBy<TextRegion> { it.box.left }
        }
        return bands.flatMap { it.sortedWith(cmp) }
    }
}
