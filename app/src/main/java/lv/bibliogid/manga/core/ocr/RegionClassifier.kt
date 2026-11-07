package lv.bibliogid.manga.core.ocr

/**
 * Определяет тип региона по пиксельным метрикам (без Tesseract и без OpenCV):
 *  - облачко: светлый фон, тёмный текст, ровная рамка низкой «зашумленности»;
 *  - мысль: как облачко, но контур пунктирный/волнистый (высокая edge-плотность кольца);
 *  - плашка/подпись: прямоугольная, часто во всю ширину, фон не белый а серый/цветной;
 *  - свободный текст: текст поверх рисунка (расхождение яркости текста и кольца мало или наоборот);
 *  - SFX: очень крупные глифы и мало символов.
 */
object RegionClassifier {

    fun classify(m: RegionMetrics): RegionType {
        val contrast = m.insideLuma - m.ringLuma

        // SFX — крупные глифы, короткий текст
        if (m.glyphHeightPx >= 46f && m.chars <= 8) return RegionType.SFX
        if (m.glyphHeightPx >= 34f && m.chars <= 4 && m.inkDensity in 0.05..0.6) return RegionType.SFX

        // плашка: прямоугольная, заполняет ширину, фон светлый/серый и однородный
        if (m.ringLuma >= 150.0 && m.insideLuma <= 140.0 && m.ringEdgeDensity < 0.28) {
            return if (m.columnFillRatio > 0.72) RegionType.CAPTION else RegionType.BUBBLE
        }

        // мысль: облачко, но контур рваный
        val bubbleLike = m.ringLuma >= 180.0 && m.insideLuma <= 130.0 && m.ringEdgeDensity < 0.55
        if (bubbleLike) {
            return if (m.ringEdgeDensity > 0.34) RegionType.THOUGHT else RegionType.BUBBLE
        }

        // светлый текст на тёмном рисунке (частый случай в вебтунах)
        if (contrast < -40.0 && m.inkDensity < 0.45) return RegionType.FREE_TEXT

        // текст на «шумном» фоне без облачка
        if (m.ringEdgeDensity >= 0.5) return RegionType.FREE_TEXT

        return if (m.ringLuma > 165.0) RegionType.BUBBLE else RegionType.FREE_TEXT
    }

    /** Читается ли регион как диалог (нужен для TTS и порядка чтения). */
    fun isDialogue(type: RegionType): Boolean =
        type == RegionType.BUBBLE || type == RegionType.THOUGHT || type == RegionType.FREE_TEXT || type == RegionType.CAPTION
}
