package lv.bibliogid.manga.core.text

import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class DbgTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() { WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt")) }
    }

    @Test
    fun dump() {
        val n = TextNormalizer(joinMode = JoinMode.REGION)
        val cases = listOf(
            listOf("УЖЕ ПОРА НА-", "ЧИНАТЬ ВСТРЕЧУ,", "ТАК ЧТО ЕЁ ВЫСО-", "ЧЕСТВО, ДОЛЖНО", "БЫТЬ, ПРИБЫЛА."),
            listOf("не УВЕРЕН,", "КАК СТОИТ СКА-", "ЗАТЬ ЭТО, НО..."),
            listOf("В БУДУЩЕМ,", "КОГДА ВЫ БУДЕ-", "ТЕ ГОВОРИТЬ С ЕЁ", "ВЫСОЧЕСТВОМ..."),
            listOf("ЛУЧШЕ БЫТЬ.", "БОЛЕЕ РАВНОДУШНЫМ.")
        )
        val sb = StringBuilder()
        cases.forEachIndexed { i, lines ->
            val r = n.normalize(lines)
            sb.append("case ").append(i).append(": ").append(r.replicas.joinToString(" | ")).append("\n")
        }
        File("/home/user/dbg.txt").writeText(sb.toString())
    }
}
