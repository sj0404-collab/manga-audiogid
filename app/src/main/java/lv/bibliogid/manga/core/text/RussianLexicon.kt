package lv.bibliogid.manga.core.text

/**
 * Небольшой частотный словарь русских слов.
 * Используется ТОЛЬКО для восстановления слов, в которых OCR оставил цифры/символы
 * (например «П2ЖАЛИСТА» -> «пожалуйста»). Обычные слова не трогаются.
 */
class RussianLexicon private constructor(private val words: Set<String>) {

    val size: Int get() = words.size

    fun contains(word: String): Boolean = words.contains(word.lowercase().replace('ё', 'е'))

    /** Кандидаты той же длины, подходящие под маску (null = любая буква). */
    fun maskMatches(mask: List<Char?>): List<String> {
        if (mask.isEmpty() || mask.size > 24) return emptyList()
        val out = ArrayList<String>(4)
        for (w in words) {
            if (w.length != mask.size) continue
            var ok = true
            for (i in w.indices) {
                val m = mask[i] ?: continue
                if (w[i] != m) { ok = false; break }
            }
            if (ok) {
                out.add(w)
                if (out.size >= 6) break
            }
        }
        return out
    }

    /** Ближайшее слово из словаря в пределах distance (только когда слово уже битое OCR). */
    fun fuzzy(word: String, maxDistance: Int): String? {
        val w = word.lowercase().replace('ё', 'е')
        if (w.length < 4) return null
        var best: String? = null
        var bestD = maxDistance + 1
        for (cand in words) {
            val lenDiff = kotlin.math.abs(cand.length - w.length)
            if (lenDiff > 2) continue
            val d = levenshtein(w, cand, maxDistance)
            if (d < bestD) { bestD = d; best = cand }
        }
        return if (bestD <= maxDistance) best else null
    }

    companion object {
        fun default(): RussianLexicon = RussianLexicon(BASE_WORDS.split(' ', ',', '\n')
            .filter { it.isNotBlank() }
            .map { it.lowercase().replace('ё', 'е') }
            .toSet())

        fun of(vararg words: String): RussianLexicon =
            RussianLexicon(words.map { it.lowercase().replace('ё', 'е') }.filter { it.isNotBlank() }.toSet())

        internal fun levenshtein(a: String, b: String, cap: Int = Int.MAX_VALUE): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (cur[j] < rowMin) rowMin = cur[j]
                }
                if (rowMin > cap) return cap + 1
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        private val BASE_WORDS = """
        и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по только ее мне
        было вот от меня еще нет о из ему теперь когда даже ну вдруг ли если уже или ни быть был него до
        вас нибудь опять уж вам ведь там потом себя ничего ей может они тут где есть надо ней для мы тебя
        их чем была сам чтоб без будто чего раз тоже себе под будет ж тогда кто этот того потому этого
        какой совсем ним здесь этом один почти мой тем чтобы нее сейчас были куда зачем всех никогда можно
        при наконец два об другой хоть после над больше тот через эти нас про всего них какая много разве
        три эту моя впрочем хорошо свою этой перед иногда лучше чуть том нельзя такой им более всегда
        конечно всю между это как-то почему-то пожалуйста спасибо привет здравствуйте извини извините
        прости простите помоги помогите пожалуйста давай давайте смотри смотрите слушай слушайте стой
        стойте подожди подождите иди идите беги бегите хватит довольно точно верно ясно понял поняла
        понятно ладно хорошо плохо страшно смешно грустно радостно правда ложь правда же конечно
        девушка парень человек люди женщина мужчина друг подруга мама папа брат сестра сын дочь
        учитель ученик школа класс дом улица город страна мир время день ночь утро вечер год месяц
        неделя час минута секунда рука нога голова глаза лицо волосы сердце душа жизнь смерть любовь
        ненависть сила слабость магия заклинание меч щит король королева принц принцесса рыцарь
        господин госпожа ваше высочество величество извините прощения позвольте разрешите
        не позволите себе увлечься ее высочеством вы должны быть равнодушны как и всегда притворитесь
        что обдумываете что она говорит раза когда слушаете ее значит ты можешь и от нас уехать
        слаба богу а то мы только подружились было бы грустно если бы ты ехала подружились
        просто когда ты говорил по телефону все хорошо пи чиру точно ничего если ты останешься
        у меня на ночь кстати говоря о чем ты пять пока не обида юсь прочитано страница том глава
        экстра продолжение начало конец дальше назад вперёд впереди сзади слева справа рядом около
        здесь там куда-то где-то когда-то кто-то что-то почему потому значит стало быть вообще
        видимо наверное возможно обязательно действительно конечно разумеется как-нибудь когда-нибудь
        """.trimIndent()
    }
}
