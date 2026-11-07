package lv.bibliogid.manga.core.text

/**
 * Лечение «двойников» — букв, которые шрифт комикса рисует похоже.
 *
 * На боевых страницах встречалось: «ТРЕНАЕ БЫТЬ ПРЕДАННЫМ, когда АЕЛО ДОХОДИТ ДО ЛЮБВИ» —
 * на самом деле «ТРЕНДЕ … ДЕЛО». В комикс-шрифтах прописное «Д» рисуют как «Λ» с ножками,
 * и распознаватель (и глазами-то не отличить) читает его как «А».
 *
 * Здесь слово, которого нет в словаре, пробуем «починить» заменой таких букв на парные
 * по шрифту. Правим ТОЛЬКО то, что словарь не знает: нормальные слова и имена не трогаем
 * (если «мувон» в словарь не входит, а заменой его не спасти — он остаётся как есть).
 */
object WordRepair {

    /**
     * Пары «похожая буква -> настоящая». Список намеренно короткий: каждая пара —
     * это шанс испортить имя, поэтому берём только реально наблюдаемые путаницы.
     */
    private val CONFUSE: Map<Char, List<Char>> = mapOf(
        'а' to listOf('д'),   // Д рисуют как A
        'ь' to listOf('ъ'),
        'и' to listOf('й')
    )

    private const val MAX_FIXES = 2

    /** Правит строку по токенам, сохраняя пунктуацию и регистр. */
    fun repairLine(line: String): String {
        if (!WordFrequencies.isLoaded || line.isBlank()) return line
        return line.split(' ').joinToString(" ") { tok ->
            repairToken(tok) ?: tok
        }
    }

    /** Возвращает исправленный токен или null, если править нечем. */
    fun repairToken(token: String): String? {
        if (token.none { it.isLetter() }) return null
        val lead = token.takeWhile { !it.isLetter() }
        val trail = token.takeLastWhile { !it.isLetter() }
        val core = token.substring(lead.length, token.length - trail.length)
        if (core.length < 4) return null
        // слово с заглавной — это может быть имя: буквы вставляем в СУЩЕСТВИТЕЛЬНЫЕ только
        // когда они явно частые. Прилагательные и глаголы чиним всегда: имена так не
        // выглядят, а «Равноушный» -> «равнодушный» нужно (порог 2000 его блокировал).
        val capitalized = core.first().isUpperCase()
        val minCount = if (capitalized) 2000.0 else 100.0
        // запас над вторым кандидатом для слов с заглавной больше: имя не должно «починиться»
        val minMargin = if (capitalized) 3.0 else 2.0
        val fixed = repairWord(core, minCount, minMargin) ?: return null
        // не терять заглавную: «Серце» -> «Сердце», а не «сердце»
        val cased = if (core.first().isUpperCase()) fixed.replaceFirstChar { it.uppercaseChar() } else fixed
        return lead + cased + trail
    }

    /**
     * Ищет словарное слово, отличающееся только «шрифтовыми» буквами.
     *
     * @param minCount минимальная частота найденного слова для починки ПРОПУЩЕННОЙ буквы.
     * Для слов с заглавной буквы порог выше: «Карсель» (имя) не должно становиться
     * «карусель», а «Серце» -> «Сердце» — можно.
     */
    fun repairWord(core: String, minCount: Double = 100.0, minMargin: Double = 2.0): String? {
        if (!WordFrequencies.isLoaded) return null
        val low = core.lowercase().replace('ё', 'е')
        if (WordFrequencies.contains(low)) return null      // слово нормальное

        // Сначала — пропущенная буква: «равноушны» -> «равнодушны», «серце» -> «сердце».
        repairMissingLetter(low, minCount, minMargin)?.let { return it }

        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val positions = low.indices.filter { CONFUSE.containsKey(low[it]) }
        if (positions.isEmpty()) return null

        // одна замена
        for (p in positions) {
            for (alt in CONFUSE.getValue(low[p])) {
                val cand = low.substring(0, p) + alt + low.substring(p + 1)
                val score = candidateScore(cand) ?: continue
                if (score > bestScore) { bestScore = score; best = cand }
            }
        }
        if (best != null) return best

        // две замены (например «аеао» -> «дедо»)
        if (MAX_FIXES >= 2) {
            for (i in positions.indices) {
                for (j in i + 1 until positions.size) {
                    val p = positions[i]
                    val q = positions[j]
                    for (a1 in CONFUSE.getValue(low[p])) {
                        for (a2 in CONFUSE.getValue(low[q])) {
                            val sb = StringBuilder(low)
                            sb.setCharAt(p, a1)
                            sb.setCharAt(q, a2)
                            val cand = sb.toString()
                            val score = candidateScore(cand) ?: continue
                            if (score > bestScore) { bestScore = score; best = cand }
                        }
                    }
                }
            }
        }
        return best
    }

    /**
     * Оценка кандидата: точная словоформа словаря — её log-вероятность,
     * подходящая ОСНОВА («обдумываете» при словарном «обдумываю») — низкий фиксированный балл.
     * Так точное слово всегда выигрывает у «морфологически похожего».
     */
    private fun candidateScore(cand: String): Double? {
        if (WordFrequencies.contains(cand)) return WordFrequencies.logProb(cand)
        if (WordForms.isPlausible(cand)) return STEM_ONLY_SCORE
        return null
    }

    /** Балл для кандидата, подтверждённого только основой (всегда хуже точного слова). */
    private const val STEM_ONLY_SCORE = -25.0

    private const val RUS_LETTERS = "абвгдежзийклмнопрстуфхцчшщъыьэюя"

    /** Окончания прилагательных, причастий и глаголов (имена так почти не кончаются). */
    private val INFLECTED = listOf(
        "ый", "ий", "ой", "ая", "яя", "ое", "ее", "ые", "ие", "ого", "его", "ому", "ему",
        "ым", "им", "ых", "их", "ую", "юю", "ешь", "ишь", "ет", "ит", "ют", "ут", "ат", "ят",
        "ала", "ало", "али", "ться", "тся", "ющий", "вший", "нный", "тый",
        "ны", "на", "но", "ен"
    )

    private fun isInflected(w: String): Boolean = INFLECTED.any { w.endsWith(it) }

    /**
     * Починка слова, в котором OCR ПОТЕРЯЛ букву: «равноушны» -> «равнодушны», «серце» -> «сердце».
     *
     * Работает только по ТОЧНЫМ словарным словам и только если вариант ровно один
     * (или лучший заметно вероятнее второго) — иначе вставлять буквы в имена и незнакомые
     * слова было бы опаснее, чем оставить как есть.
     */
    private fun repairMissingLetter(low: String, minCount: Double, minMargin: Double): String? {
        if (low.length !in 5..18) return null
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        for (pos in 1..low.length) {
            for (ch in RUS_LETTERS) {
                val cand = low.substring(0, pos) + ch + low.substring(pos)
                if (!WordFrequencies.contains(cand)) continue
                val score = WordFrequencies.logProb(cand)
                if (score > bestScore) { secondScore = bestScore; bestScore = score; best = cand }
                else if (score > secondScore) secondScore = score
            }
        }
        val found = best ?: return null
        // вариант должен быть реально частым и выигрывать у второго с запасом.
        // Для прилагательных/глаголов порог ниже: имена существительными-прилагательными
        // на «-ый», «-ая», «-ишь» не заканчиваются, а «равнодушный» (420) при пороге 2000
        // не чинился, если слово стояло с заглавной буквы.
        val need = if (isInflected(found)) minOf(minCount, 100.0) else minCount
        if (WordFrequencies.count(found) < need) return null
        if (bestScore - secondScore < minMargin) return null
        return found
    }

}
