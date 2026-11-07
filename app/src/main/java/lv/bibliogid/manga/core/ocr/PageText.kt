package lv.bibliogid.manga.core.ocr

import lv.bibliogid.manga.core.text.JoinMode
import lv.bibliogid.manga.core.text.TextNormalizer

/** Одна отрендеренная реплика страницы — одна запись в панели «Текст страницы» и одна озвучка. */
data class Replica(
    val index: Int,
    val text: String,
    val type: RegionType,
    val box: Box,
    val needsReview: Boolean = false,
    /** Номер полосы (панели) страницы, с 0. */
    val bandIndex: Int = 0,
    /** Рамки отдельных строк — по ним рисуется подсветка (ровно по тексту). */
    val lines: List<Box> = emptyList(),
    /**
     * «Молчание»: в облачке только многоточие («…»). Такое облачко надо ПОКАЗАТЬ
     * рамкой (иначе кажется, что страница перепрыгнула реплику), но читать нечего —
     * голосом оно не озвучивается.
     */
    val silence: Boolean = false,
    /**
     * Реплика «не закончена»: в сыром OCR в конце нет точки/!/?/многоточия — фраза
     * продолжается. Обычно она продолжается на следующей странице («Но разве.» на стр.2
     * и «Наш лидер уже недостаточно плох?» на стр.3) — такую пару надо озвучить одним
     * предложением, см. CrossPage.
     */
    val openEnded: Boolean = false
)

data class PageText(
    val replicas: List<Replica>,
    val engine: String,
    val elapsedMs: Long,
    val rawLineCount: Int,
    val cleanLineCount: Int,
    val latinLeft: Int,
    val warnings: List<String> = emptyList(),
    /** Сырой текст ровно как отдал движок OCR — для секции «как распознано». */
    val rawText: String = "",
    /** Текст взят из кеша (распознано раньше), а не получен сейчас. */
    val fromCache: Boolean = false,
    val bandCount: Int = 1,
    /** Размер картинки, в координатах которой заданы box'ы реплик (для рамок на странице). */
    val pageWidth: Int = 0,
    val pageHeight: Int = 0
) {
    val isEmpty: Boolean get() = replicas.isEmpty()

    /** Сколько реплик — «молчание» (многоточие, не озвучивается). */
    val silenceCount: Int get() = replicas.count { it.silence }
    val fullText: String get() = replicas.joinToString("\n") { it.text }

    /** Реплики конкретной полосы. */
    fun band(index: Int): List<Replica> = replicas.filter { it.bandIndex == index }

    fun withCacheFlag(cached: Boolean): PageText = copy(fromCache = cached)
}

/**
 * Многоточие вместо реплики: «…», «...», «··», «— … —».
 *
 * Из журнала 20:24: детектор находил такие облачка, а из текста они исчезали
 * (нормализатор вычищал точки), поэтому в ленте оставалась «пустая» рамка, и
 * последовательность реплик выглядела так, будто страница перепрыгнула облачко.
 */
object Silence {

    private val allowed = charArrayOf('.', ',', '…', '·', '•', '-', '–', '—', '~', '\'', '"', '!', '?', ':', ';')

    /** Текст, состоящий только из точек/тире: читать нечего. */
    fun isSilence(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.any { it.isLetterOrDigit() }) return false
        val hasDots = t.any { it == '.' || it == '…' || it == '·' || it == '•' }
        if (!hasDots) return false
        return t.all { c -> c in allowed || c.isWhitespace() }
    }

    /**
     * Многоточие было в сырых строках движка, но нормализатор его вычистил.
     * Считаем молчанием, если в строке не меньше двух точек и после удаления
     * букв/цифр текст всё ещё «молчание».
     */
    fun inRaw(rawLines: List<String>): Boolean {
        val joined = rawLines.joinToString(" ")
        var dots = 0
        var letters = 0
        for (c in joined) {
            // «…» — это уже многоточие, считаем его за две точки
            if (c == '…') dots += 2 else if (c == '.' || c == '·' || c == '•') dots++
            if (c.isLetterOrDigit()) letters++
        }
        if (dots < 2) return false
        // буквы есть — это фраза, а не молчание (движок иногда цепляет одну букву)
        if (letters > 1) return false
        return isSilence(joined.filter { !it.isLetterOrDigit() })
    }
}

/**
 * Финальная сборка текста страницы: регионы -> порядок чтения -> чистый текст.
 * Гарантии:
 *  - ни одна непустая строка не теряется (пустой регион помечается needsReview);
 *  - в тексте нет латиницы/греческого/мусора (политика нормализатора);
 *  - единица показа — цельное предложение (реплика), а не строка OCR.
 */
class PageTextBuilder(
    private val normalizer: TextNormalizer = TextNormalizer(joinMode = JoinMode.REGION)
) {

    fun build(
        regions: List<TextRegion>,
        mode: ReadingMode,
        engine: String,
        elapsedMs: Long,
        rawTextOverride: String? = null,
        pageWidth: Int = 0,
        pageHeight: Int = 0,
        /** Доращивание рамки региона до границ облачка (по яркости страницы). */
        grower: ((Box) -> Box)? = null
    ): PageText {
        // 1) склеиваем блоки, разрезанные полосами (иначе предложение рвётся на части)
        val blocks = BlockMerger.merge(regions.filter { it.rawLines.isNotEmpty() }, pageHeight)
        // 1.5) регион, в который слиплись ДВА облачка («СТОП, ВСЁ…» + «ЭТО НЕ ЛЮБОВНЫЕ
        // ОТНОШЕНИЯ!» на стр.20), режем по разрыву между строками — иначе чужие фразы
        // звучат одной репликой и последние строки первой теряются.
        val pieces = blocks.flatMap { RegionSplitter.split(it) }
        // 2) порядок чтения
        val ordered = ReadingOrder.order(pieces, mode)
        val replicas = ArrayList<Replica>(ordered.size)
        var latin = 0
        var rawLines = 0
        var cleanLines = 0
        var decorative = 0
        val warnings = ArrayList<String>()

        ordered.forEachIndexed { i, regionRaw ->
            val region = if (grower != null) regionRaw.copy(box = grower(regionRaw.box)) else regionRaw
            // «Фраза не закончена» смотрим ДО нормализатора: он дописывает точку,
            // и без этого признака склейка через границу страниц невозможна.
            val rawTail = region.rawLines.joinToString(" ").trim().lastOrNull { !it.isWhitespace() }
            val openEnded = rawTail != null && rawTail !in ".!?…»\"'"
            rawLines += region.rawLines.count { it.isNotBlank() }
            val res = normalizer.normalize(region.rawLines)
            cleanLines += res.replicas.size
            latin += res.report.latinLeft
            res.report.warnings.forEach { if (it !in warnings) warnings += it }
            if (res.replicas.isEmpty()) {
                // строки были, но нормализатор всё вычистил: показываем хотя бы то, что
                // реально прочитал движок (без мусорных символов), но помечаем «проверить»
                val fallback = region.rawLines
                    .joinToString(" ")
                    .replace(Regex("[\\u0370-\\u03FF\\u2190-\\u21FF\\u2500-\\u257F$%^~|\\\\<>*_=+@#&]"), " ")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
                if (Silence.inRaw(region.rawLines)) {
                    replicas.add(
                        Replica(
                            index = replicas.size,
                            text = "…",
                            type = region.type,
                            box = region.box,
                            lines = region.lineBoxes,
                            silence = true,
                            openEnded = openEnded
                        )
                    )
                    lv.bibliogid.manga.core.util.AppLog.d(
                        "ocr",
                        "молчание «…» [${region.box.left.toInt()},${region.box.top.toInt()}," +
                            "${region.box.right.toInt()},${region.box.bottom.toInt()}] — не озвучивается"
                    )
                    return@forEachIndexed
                }
                // Облачко без единой буквы, но размером в треть страницы — это пустая
                // «ванна» на рисунке, а не реплика. В журнале 1.6.16 такая область дала
                // рамку на 38 % высоты страницы и «съела» соседние облачка.
                val pageArea = pageWidth.toFloat() * pageHeight.toFloat()
                val hugeEmpty = !fallback.any { it.isLetter() } && pageArea > 0f &&
                    region.box.area > pageArea * 0.03f
                if (hugeEmpty) {
                    decorative++
                    lv.bibliogid.manga.core.util.AppLog.d(
                        "ocr",
                        "пустая область пропущена [${region.box.left.toInt()},${region.box.top.toInt()}," +
                            "${region.box.right.toInt()},${region.box.bottom.toInt()}]: " +
                            "${(region.box.area / pageArea * 100).toInt()}% страницы, букв нет"
                    )
                    return@forEachIndexed
                }
                val fallbackReason = DecorativeFilter.reason(fallback, region.box, pageWidth, pageHeight)
                if (fallbackReason != null) {
                    decorative++
                    lv.bibliogid.manga.core.util.AppLog.d(
                        "ocr",
                        "пропущено как декор ($fallbackReason): $fallback"
                    )
                    return@forEachIndexed
                }
                replicas.add(
                    Replica(
                        index = replicas.size,
                        text = if (fallback.any { it.isLetter() }) fallback else "",
                        type = region.type,
                        box = region.box,
                        needsReview = true,
                        lines = region.lineBoxes,
                        openEnded = openEnded
                    )
                )
                return@forEachIndexed
            }
            // ОДИН регион (облачко/плашка) = ОДНА реплика. Раньше каждая фраза внутри
            // облачка становилась отдельной репликой, и текст читался «по кусочкам»:
            // «ОДНАКО.», «Я НИЧЕГО НЕ МОГУ ПОДЕЛАТЬ.», «ЕСЛИ ЛЮДИ ПОЙМУТ…».
            // Теперь облачко звучит целиком, пауза — только между облачками.
            var joined = BubbleJoiner.join(res.replicas)
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            // Страховка: если строки региона не попали в реплику (слипшиеся облачка),
            // дописываем их, чтобы «последние строки» не пропадали из озвучки.
            val lost = LostLines.findLost(joined, region.rawLines)
            if (lost.isNotEmpty()) {
                joined = BubbleJoiner.join(listOf(joined) + lost)
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
                lost.forEach { l ->
                    lv.bibliogid.manga.core.util.AppLog.d("ocr", "строка дописана в реплику: $l")
                }
                if (lost.isNotEmpty()) warnings += "восстановлено строк: ${lost.size}"
            }
            if (Silence.isSilence(joined)) {
                replicas.add(
                    Replica(
                        index = replicas.size,
                        text = "…",
                        type = region.type,
                        box = region.box,
                        lines = region.lineBoxes,
                        silence = true,
                        openEnded = openEnded
                    )
                )
                lv.bibliogid.manga.core.util.AppLog.d(
                    "ocr",
                    "молчание «…» [${region.box.left.toInt()},${region.box.top.toInt()}," +
                        "${region.box.right.toInt()},${region.box.bottom.toInt()}] — не озвучивается"
                )
                return@forEachIndexed
            }
            val skipReason = DecorativeFilter.reason(joined, region.box, pageWidth, pageHeight)
            val skip = skipReason != null
            if (skip) {
                decorative++
                // в журнале видно, ЧТО именно отсеяли и почему — иначе «пропало облачко» не разобрать
                lv.bibliogid.manga.core.util.AppLog.d(
                    "ocr",
                    "пропущено как декор ($skipReason) [${region.box.left.toInt()},${region.box.top.toInt()}," +
                        "${region.box.right.toInt()},${region.box.bottom.toInt()}]: $joined"
                )
            }
            if (joined.isNotEmpty() && !skip) {
                replicas.add(
                    Replica(
                        index = replicas.size,
                        text = joined,
                        type = region.type,
                        box = region.box,
                        needsReview = joined.count { it.isLetter() } < 3,
                        lines = region.lineBoxes,
                        openEnded = openEnded
                    )
                )
            }
        }
        if (decorative > 0) warnings += "пропущено нетекстовых строк: $decorative"
        val finalReplicas = replicas.filter { it.text.isNotBlank() || it.needsReview }
        val bandIndexes = Bands.assign(finalReplicas.map { it.box })
        val bandCount = Bands.count(bandIndexes).coerceAtLeast(if (finalReplicas.isEmpty()) 0 else 1)
        val withBands = finalReplicas.mapIndexed { i, r -> r.copy(bandIndex = bandIndexes.getOrElse(i) { 0 }) }
        val rawText = rawTextOverride ?: ordered.joinToString(" ") { it.rawLines.joinToString(" ") }
        return PageText(
            replicas = withBands,
            engine = engine,
            elapsedMs = elapsedMs,
            rawLineCount = rawLines,
            cleanLineCount = cleanLines,
            latinLeft = latin,
            warnings = warnings,
            rawText = rawText,
            bandCount = bandCount,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )
    }
}
