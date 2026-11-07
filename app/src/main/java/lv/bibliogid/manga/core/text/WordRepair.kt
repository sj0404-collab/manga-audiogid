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
        val fixed = repairWord(core) ?: return null
        return lead + fixed + trail
    }

    /** Ищет словарное слово, отличающееся только «шрифтовыми» буквами. */
    fun repairWord(core: String): String? {
        if (!WordFrequencies.isLoaded) return null
        val low = core.lowercase().replace('ё', 'е')
        if (WordFrequencies.contains(low)) return null      // слово нормальное

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

}
