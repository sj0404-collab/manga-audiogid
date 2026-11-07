package lv.bibliogid.manga.core.ocr

/** Прямоугольник в координатах страницы. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    fun verticalOverlap(other: Box): Float {
        val top2 = maxOf(top, other.top)
        val bottom2 = minOf(bottom, other.bottom)
        return (bottom2 - top2).coerceAtLeast(0f)
    }

    fun horizontalOverlap(other: Box): Float {
        val l = maxOf(left, other.left)
        val r = minOf(right, other.right)
        return (r - l).coerceAtLeast(0f)
    }

    fun union(other: Box) = Box(
        minOf(left, other.left), minOf(top, other.top),
        maxOf(right, other.right), maxOf(bottom, other.bottom)
    )

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

/** Тип текстового региона на странице — от него зависит и порядок, и подача. */
enum class RegionType {
    /** Круглое/овальное облачко речи. */
    BUBBLE,
    /** Мысли/шёпот (пунктирная рамка, «облако»). */
    THOUGHT,
    /** Прямоугольная плашка-комментарий/надпись автора. */
    CAPTION,
    /** Текст прямо поверх рисунка, без облачка. */
    FREE_TEXT,
    /** Звуковой эффект (ХЛОП, БАМ). */
    SFX,
    /** Не определить. */
    UNKNOWN
}

/** Пиксельные метрики региона, по которым классификатор определяет тип. */
data class RegionMetrics(
    val insideLuma: Double,
    val ringLuma: Double,
    val inkDensity: Double,
    val ringEdgeDensity: Double,
    val glyphHeightPx: Float,
    val regionWidthPx: Float,
    val chars: Int,
    val columnFillRatio: Double = 0.5
)

/** Готовый регион: рамка + метрики + строки текста в порядке чтения. */
data class TextRegion(
    val box: Box,
    val type: RegionType,
    val rawLines: List<String>,
    val metrics: RegionMetrics? = null,
    /** Рамки самих строк текста — по ним рисуется рамка, точно по буквам. */
    val lineBoxes: List<Box> = emptyList()
) {
    val isDialogue: Boolean get() = type == RegionType.BUBBLE || type == RegionType.THOUGHT || type == RegionType.FREE_TEXT
}

/** Строка с координатами (из локального или облачного OCR). */
data class OcrLine(val text: String, val box: Box, val confidence: Float = 1f)
