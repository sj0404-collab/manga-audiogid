package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceAssemblerTest {

    private val asm = SentenceAssembler(joinMode = JoinMode.REGION)

    @Test
    fun `склеивает строки одного облачка в цельное предложение`() {
        val out = asm.assemble(listOf(
            "ВЫ ДОЛЖНЫ БЫТЬ",
            "РАВНОДУШНЫ, КАК И",
            "ВСЕГДА. ПРИТВОРИТЕСЬ,",
            "ЧТО ОБДУМЫВАЕТЕ"
        ))
        assertEquals(1, out.size)
        assertEquals("Вы должны быть равнодушны, как и всегда. Притворитесь, что обдумываете.", out[0])
    }

    @Test
    fun `склеивает перенос через дефис`() {
        val out = asm.assemble(listOf("СЛУША-", "ТЕ ЕЁ"))
        assertEquals(listOf("Слушате её."), out)
    }

    @Test
    fun `не склеивает разные законченные реплики`() {
        val out = asm.assemble(listOf("СТОЙ!", "ПОЧЕМУ ТЫ ЗДЕСЬ?"))
        assertEquals(2, out.size)
        assertEquals("Стой!", out[0])
        assertEquals("Почему ты здесь?", out[1])
    }

    @Test
    fun `добавляет точку, если предложение не закончено`() {
        val out = asm.assemble(listOf("БЫЛО БЫ ГРУСТНО, ЕСЛИ БЫ ТЫ ЕХАЛА"))
        assertEquals(listOf("Было бы грустно, если бы ты ехала."), out)
    }

    @Test
    fun `висячий союз в конце строки — признак продолжения`() {
        val conservative = SentenceAssembler()
        assertTrue(conservative.shouldJoin("ОН ПОШЁЛ В", "МАГАЗИН ЗА ХЛЕБОМ"))
    }

    @Test
    fun `режим сохранения капса`() {
        val keep = SentenceAssembler(casePolicy = CasePolicy.KEEP_AS_IS, joinMode = JoinMode.REGION)
        val out = keep.assemble(listOf("СТОЙ ТЫ КУДА"))
        assertEquals("СТОЙ ТЫ КУДА.", out[0])
    }
}
