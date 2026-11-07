package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import lv.bibliogid.manga.core.text.WordFrequencies

/** Рамка региона доращивается до контура облачка (журнал: «[95,1191,303,1311]» — левая часть). */
class BoxGrowerTest {

    private fun page(w: Int, h: Int, light: Int = 250): IntArray = IntArray(w * h) { light }

    @Test
    fun growsToBubbleContour() {
        val w = 200
        val h = 120
        val luma = page(w, h)
        for (y in 10 until 110) {
            for (x in 20 until 180) {
                val border = x < 23 || x > 176 || y < 13 || y > 106
                luma[y * w + x] = if (border) 20 else 250
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (x !in 20 until 180 || y !in 10 until 110) luma[y * w + x] = 40
        }

        val text = Box(60f, 40f, 110f, 70f)
        val grown = BoxGrower.grow(luma, w, h, text)
        assertTrue("рамка должна вырасти по ширине: $grown", grown.width >= text.width * 2.4f)
        assertTrue("рамка должна вырасти по высоте: $grown", grown.height >= text.height * 1.8f)
        assertTrue("рамка должна дойти до левого контура: $grown", grown.left <= 30f)
    }

    @Test
    fun doesNotGrowIntoDarkArt() {
        val w = 200
        val h = 120
        val luma = page(w, h, light = 40)
        val text = Box(60f, 40f, 110f, 70f)
        assertEquals("не должен расти по тёмному рисунку", text, BoxGrower.grow(luma, w, h, text))
    }

    @Test
    fun thinLineGrowsUpToTallBubble() {
        // облачко 300x200, а распознана одна строка 270x43 — рамка должна накрыть пузырь
        val w = 400
        val h = 300
        val luma = page(w, h)
        for (y in 40 until 240) for (x in 50 until 350) {
            val border = x < 53 || x > 346 || y < 43 || y > 236
            luma[y * w + x] = if (border) 20 else 250
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (x !in 50 until 350 || y !in 40 until 240) luma[y * w + x] = 40
        }
        val oneLine = Box(60f, 100f, 330f, 143f)
        val grown = BoxGrower.grow(luma, w, h, oneLine)
        assertTrue("рамка накрыла облачко по высоте: $grown", grown.height >= 150f)
    }

    @Test
    fun neverGrowsBeyondLimits() {
        val w = 400
        val h = 300
        val luma = page(w, h)
        val text = Box(180f, 140f, 220f, 160f)
        val grown = BoxGrower.grow(luma, w, h, text, maxGrowXFactor = 0.5f, maxGrowYFactor = 0.5f)
        // рост ограничен долей размера региона (для тонкого бокса допускается больше по
        // вертикали: иначе рамка не достаёт до контура высокого облачка)
        assertTrue("рост по X ограничен: $grown", grown.width <= text.width * 2.6f)
        assertTrue("рост по Y ограничен", grown.height <= text.height * 3.6f)
    }
}


    /**
     * Журнал 1.6.9, стр.16: рамка выросла до [0,1321,719,2875] — полстраницы.
     * На полностью белом фоне рост не упирается в контур, поэтому работает только предел.
     */
    @Test
    fun `рамка не разъезжается на полстраницы`() {
        val w = 200
        val h = 400
        val luma = IntArray(w * h) { 255 }          // сплошной белый фон, контура нет
        val text = Box(60f, 150f, 140f, 190f)       // 80 x 40
        val grown = BoxGrower.grow(luma, w, h, text)
        assertTrue("рост по X ограничен: $grown", grown.width <= text.width * 2.6f + 9f)
        assertTrue("рост по Y ограничен: $grown", grown.height <= text.height * 3.6f + 9f)
        assertTrue("рамка не выше страницы", grown.top >= 0f && grown.bottom <= h.toFloat())
    }

/** Отсев декоративных заголовков и номеров глав («Жак прожить жизнь…», «48», «T4d»). */
class DecorativeFilterTest {

    companion object {
        @BeforeClass @JvmStatic
        fun loadDict() {
            WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt"))
        }
    }

    private val pageW = 720
    private val pageH = 2960

    @Test
    fun titleBannerIsDecorative() {
        assertTrue(
            DecorativeFilter.isDecorative(
                "Жак прожить жизнь, оуеуиь смаршеи даже.", Box(87f, 269f, 633f, 462f), pageW, pageH
            )
        )
    }

    @Test
    fun chapterNumberIsDecorative() {
        assertTrue(DecorativeFilter.isDecorative("48", Box(307f, 662f, 419f, 701f), pageW, pageH))
        assertTrue(DecorativeFilter.isDecorative("T4d", Box(300f, 2500f, 380f, 2540f), pageW, pageH))
    }

    @Test
    fun singleLetterIsNotText() {
        assertTrue(DecorativeFilter.isDecorative("Е", Box(561f, 2031f, 609f, 2074f), pageW, pageH))
        assertTrue(DecorativeFilter.isDecorative("R", Box(10f, 100f, 40f, 140f), pageW, pageH))
    }

    /**
     * Журнал 1.6.8 (20:54), страница 4: фильтр срезал живой диалог.
     *   «пропущено как декор [130,0,436,129]: Не позволяйте себе увлечься её высочеством.»
     *   «пропущено как декор [325,421,612,654]: Вы должны быть равноадушны…»
     * Оба облачка — настоящие реплики в верхней части страницы.
     */
    @Test
    fun dialogueInPageTopSurvives() {
        assertFalse(
            DecorativeFilter.isDecorative(
                "Не позволяйте себе увлечься её высочеством.",
                Box(130f, 0f, 436f, 129f), pageW, pageH
            )
        )
        assertFalse(
            DecorativeFilter.isDecorative(
                "Вы должны быть равноадушны. Как и всегда. При творите сь, что обаумываете " +
                    "что она говорит 2-з раза. Когда слушаете её.",
                Box(325f, 421f, 612f, 654f), pageW, pageH
            )
        )
    }

    /** Причина отсева должна попадать в журнал: без неё «пропало облачко» не разобрать. */
    @Test
    fun decorativeReasonIsReported() {
        assertEquals(
            "заставка-название",
            DecorativeFilter.reason("Вак прожить жизнь, будиие старшей ее трой мир ана.", Box(0f, 0f, 719f, 794f), pageW, pageH)
        )
        assertNull(
            DecorativeFilter.reason("Не позволяйте себе увлечься её высочеством.", Box(130f, 0f, 436f, 129f), pageW, pageH)
        )
    }

    /**
     * Журнал 1.6.9 (21:52), последняя страница главы: всё это читалось голосом,
     * хотя это название тайтла и подпись команды перевода.
     */
    @Test
    fun creditsOnLastPageAreDecorative() {
        // латиница внутри «слов»
        assertTrue(
            DecorativeFilter.isDecorative(
                "Мак прожит жизнь, оуаучиь старшей сесmbои mbана.",
                Box(0f, 2222f, 719f, 2825f), pageW, 3083
            )
        )
        // ключевое слово подписи
        assertTrue(
            DecorativeFilter.isDecorative(
                "Перевол аѕѕеу теам лuоllт тlл.",
                Box(135f, 2713f, 588f, 2957f), pageW, 3083
            )
        )
        // водяной знак во всю ширину, из словаря узнано одно служебное слово
        assertTrue(
            DecorativeFilter.isDecorative(
                "Со аѕѕеутеам.", Box(0f, 2887f, 719f, 3082f), pageW, 3083
            )
        )
    }

    @Test
    fun linksAndTeamCreditsAreDecorative() {
        assertTrue(
            DecorativeFilter.isDecorative("vk.com/assleyteam", Box(100f, 2700f, 600f, 2760f), pageW, 3083)
        )
        assertTrue(
            DecorativeFilter.isDecorative("ПЕРЕВОД ASSLEY TEAM", Box(140f, 1400f, 580f, 1500f), pageW, 3083)
        )
    }

    /** Короткие настоящие реплики не должны попадать под «водяной знак». */
    @Test
    fun shortReplicasSurvive() {
        assertFalse(DecorativeFilter.isDecorative("Хмм.", Box(256f, 946f, 488f, 1057f), pageW, 3081))
        assertFalse(DecorativeFilter.isDecorative("Разве?", Box(245f, 540f, 485f, 698f), pageW, 3083))
        assertFalse(DecorativeFilter.isDecorative("Мусор.", Box(267f, 1493f, 374f, 1516f), pageW, 2960))
    }

    /** Многоточие — это облачко-молчание, а не декор: его нельзя выбрасывать. */
    @Test
    fun silenceIsNotDecorative() {
        assertFalse(DecorativeFilter.isDecorative("…", Box(410f, 320f, 640f, 430f), pageW, pageH))
    }

    @Test
    fun normalDialogueSurvives() {
        assertFalse(
            DecorativeFilter.isDecorative(
                "Ты должна была сказать привет, Ким Мувон.",
                Box(95f, 1191f, 303f, 1311f), pageW, pageH
            )
        )
        assertFalse(
            DecorativeFilter.isDecorative(
                "Уже пора начинать встречу.", Box(100f, 300f, 500f, 420f), pageW, pageH
            )
        )
    }
}
