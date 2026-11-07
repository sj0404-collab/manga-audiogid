package lv.bibliogid.manga.core.ocr

/**
 * Склейка «блоков» текста до нормализации.
 *
 * Проблема: полоса (band) — это горизонтальная лента, и облачко, которое её пересекает,
 * режется на несколько регионов. Дальше нормализатор обрабатывает каждый регион отдельно,
 * и одно предложение превращается в три реплики: «ПРИЧИНА, ПО КОТОРОЙ МЫ ЗНАЕМ ДРУГ.»
 * «ДРУГА, ТАК ЭТО ТО, ЧТО МЫ БЫЛИ В ОДНОМ.» «КЛАССЕ ПЕРВЫЙ ГОД!», каждая с большой буквы.
 *
 * Здесь соседние по вертикали и перекрывающиеся по горизонтали регионы одного типа
 * объединяются в один блок ДО нормализации — предложение собирается целиком.
 */
object BlockMerger {

    /** Отношение перекрытия по X к ширине меньшего прямоугольника. */
    private const val MIN_X_OVERLAP = 0.25f

    /**
     * Зазор между строками ОДНОГО облачка — доли высоты строки. В облачках этого шрифта
     * межстрочный интервал доходит до 0.7 высоты, поэтому порог свободный; разные облачка
     * отделяет ещё и смещение центров (см. CENTER_TOLERANCE).
     */
    private const val LOOSE_GAP_RATIO = 0.9f

    /** Если предыдущая строка закончилась точкой/!/? — склеиваем только совсем вплотную. */
    private const val TERMINAL_GAP_RATIO = 0.35f

    /** Насколько могут разъехаться центры строк одного облачка (доли ширины). */
    private const val CENTER_TOLERANCE = 0.35f

    /** Если предыдущая строка обрывается дефисом (слово разрезано) — допускаем зазор больше. */
    private const val HYPHEN_GAP_RATIO = 1.4f

    fun merge(regions: List<TextRegion>, pageHeight: Int = 0): List<TextRegion> {
        if (regions.size < 2) return regions
        val ordered = regions.sortedWith(
            compareBy({ it.box.top }, { it.box.left })
        )
        val out = ArrayList<TextRegion>(ordered.size)
        for (r in ordered) {
            val last = out.lastOrNull()
            if (last != null && canMerge(last, r, pageHeight)) {
                out[out.size - 1] = TextRegion(
                    box = union(last.box, r.box),
                    type = last.type,
                    rawLines = last.rawLines + r.rawLines,
                    metrics = last.metrics ?: r.metrics,
                    lineBoxes = last.lineBoxes + r.lineBoxes
                )
            } else {
                out += r
            }
        }
        return out
    }

    private fun canMerge(a: TextRegion, b: TextRegion, pageHeight: Int): Boolean {
        // облачко и звук не смешиваем
        if (a.isDialogue != b.isDialogue) return false
        val minW = minOf(a.box.width, b.box.width).coerceAtLeast(1f)
        val maxW = maxOf(a.box.width, b.box.width).coerceAtLeast(1f)
        val minH = minOf(a.box.height, b.box.height).coerceAtLeast(1f)
        val overlapX = minOf(a.box.right, b.box.right) - maxOf(a.box.left, b.box.left)
        if (overlapX <= 0f || overlapX / minW < MIN_X_OVERLAP) return false
        // строки одного облачка стоят «в одной колонке»: центры не разъезжаются
        if (kotlin.math.abs(a.box.centerX - b.box.centerX) > maxW * CENTER_TOLERANCE) return false

        val gap = b.box.top - a.box.bottom
        if (gap < 0f) return true                       // уже пересекаются по вертикали
        val prevText = a.rawLines.lastOrNull().orEmpty().trimEnd()
        // строка обрывается дефисом — слово разрезано границей блока, склеиваем
        if (prevText.endsWith("-") || prevText.endsWith("–")) return gap <= minH * HYPHEN_GAP_RATIO
        // строго совпадают левый и правый края (плашка-комментарий, столбец текста) —
        // это точно один блок, даже если каждая строка кончается точкой
        val tol = maxW * 0.03f
        val sameEdges = kotlin.math.abs(a.box.left - b.box.left) < tol &&
            kotlin.math.abs(a.box.right - b.box.right) < tol
        if (sameEdges) return gap <= minH * 2.5f
        // предложение закончилось: это другое облачко, но если строки стоят совсем
        // вплотную (межабзацный интервал внутри одного облачка) — всё же склеиваем
        val terminal = prevText.endsWith(".") || prevText.endsWith("!") || prevText.endsWith("?") ||
            prevText.endsWith("…") || prevText.endsWith("»") || prevText.endsWith("\"")
        if (terminal) return gap <= minH * TERMINAL_GAP_RATIO
        return gap <= minH * LOOSE_GAP_RATIO
    }

    private fun union(a: Box, b: Box) = Box(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom)
    )
}
