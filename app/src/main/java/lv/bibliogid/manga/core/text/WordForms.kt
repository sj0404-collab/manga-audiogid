package lv.bibliogid.manga.core.text

/**
 * Морфологическая «разумность» слова — когда точной словоформы в словаре нет.
 *
 * Зачем: словарь на 47 466 словоформ не содержит всех форм («обдумываете», «притворитесь»,
 * «равнодушны»), поэтому [WordMender] и [WordRepair] отказывались чинить такие слова:
 * в журнале 1.6.12 оставались «обаумываете», «При творите сь», «равноаушны».
 *
 * Решение: один раз строим множество ОСНОВ словаря (слово без типичного окончания) и
 * считаем слово правдоподобным, если его основа в этом множестве совпадает. Это дешево
 * (≤47 466 строк) и не даёт «фантазировать»: набор букв должен совпасть с настоящей основой.
 */
object WordForms {

    /** Окончания от длинных к коротким: срезаем самое длинное подходящее. */
    private val ENDINGS = listOf(
        "ешься", "ишься", "етесь", "итесь", "аться", "иться", "ться", "ешься",
        "ого", "его", "ому", "ему", "ыми", "ими", "ами", "ями", "ов", "ев",
        "ый", "ий", "ой", "ая", "яя", "ое", "ее", "ые", "ие", "ую", "юю",
        "ом", "ем", "ах", "ях", "ам", "ям", "ут", "ют", "ат", "ят",
        "ешь", "ишь", "ете", "ите", "ла", "ло", "ли", "ть", "ся", "сь",
        "а", "я", "ы", "и", "у", "ю", "е", "о", "ь", "й"
    )

    private const val MIN_STEM = 4

    @Volatile private var stemSet: Set<String> = emptySet()

    val isBuilt: Boolean get() = stemSet.isNotEmpty()

    /** Строится один раз — вызывается после загрузки словаря. */
    fun ensureBuilt() {
        if (isBuilt || !WordFrequencies.isLoaded) return
        val src = WordFrequencies.words()
        val out = HashSet<String>(src.size * 2)
        for (w in src) {
            val st = stem(w)
            if (st.length >= MIN_STEM) out.add(st)
        }
        stemSet = out
    }

    /** Основа слова: срезаем самое длинное известное окончание (не короче MIN_STEM). */
    fun stem(word: String): String {
        val w = word.lowercase().replace('ё', 'е')
        for (e in ENDINGS) {
            if (w.length - e.length >= MIN_STEM && w.endsWith(e)) return w.substring(0, w.length - e.length)
        }
        return w
    }

    /**
     * Слово есть в словаре или его основа совпадает с основой словарного слова.
     * Если словарь не загружен — false (поведение прежних версий).
     */
    fun isPlausible(word: String): Boolean {
        if (!WordFrequencies.isLoaded) return false
        val w = word.lowercase().replace('ё', 'е')
        if (w.isEmpty()) return false
        if (WordFrequencies.contains(w)) return true
        ensureBuilt()
        return stemSet.contains(stem(w))
    }
}
