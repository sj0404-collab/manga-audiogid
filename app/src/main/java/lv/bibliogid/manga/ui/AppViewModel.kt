package lv.bibliogid.manga.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.LruCache
import lv.bibliogid.manga.core.ocr.CrossPage
import lv.bibliogid.manga.core.text.SpokenText
import lv.bibliogid.manga.core.util.AppLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lv.bibliogid.manga.core.library.ChapterEntry
import lv.bibliogid.manga.core.library.LibraryFilter
import lv.bibliogid.manga.core.library.LibrarySort
import lv.bibliogid.manga.core.library.MangaEntry
import lv.bibliogid.manga.core.ocr.PageText
import lv.bibliogid.manga.core.ocr.Replica
import lv.bibliogid.manga.core.text.WordFrequencies
import lv.bibliogid.manga.core.text.WordForms
import lv.bibliogid.manga.core.ocr.ReadingMode
import lv.bibliogid.manga.core.tts.Pacing
import lv.bibliogid.manga.core.tts.TtsScript
import lv.bibliogid.manga.core.tts.TtsUtterance
import lv.bibliogid.manga.data.LibraryRepository
import lv.bibliogid.manga.data.MangaItem
import lv.bibliogid.manga.data.PageSource
import lv.bibliogid.manga.data.SafStorage
import lv.bibliogid.manga.data.Settings
import lv.bibliogid.manga.ocr.MlKitOcrEngine
import lv.bibliogid.manga.ocr.ModelManager
import lv.bibliogid.manga.ocr.OcrEngine
import lv.bibliogid.manga.ocr.OcrPipeline
import lv.bibliogid.manga.ocr.OcrSpaceEngine
import lv.bibliogid.manga.ocr.PaddleOcrEngine
import lv.bibliogid.manga.ocr.TextStore
import lv.bibliogid.manga.ocr.YandexOcrEngine
import lv.bibliogid.manga.ocr.GoogleVisionOcrEngine
import lv.bibliogid.manga.tts.TtsController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed class Screen {
    data object Library : Screen()
    data class Manga(val id: String) : Screen()
    data class Reader(val mangaId: String, val chapterIndex: Int, val page: Int) : Screen()
    data object Settings : Screen()
    data object Journal : Screen()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settings = Settings(app)
    val storage = SafStorage(app)
    val repo = LibraryRepository(app, storage, settings)
    val models = ModelManager(app)
    val textStore = TextStore(app)
    private val tts = TtsController(app)

    // ---------- навигация ----------
    var screen by mutableStateOf<Screen>(Screen.Library)

    // ---------- библиотека ----------
    var library by mutableStateOf<List<MangaItem>>(emptyList())
    var scanning by mutableStateOf(false)
    var scanProgress by mutableStateOf(0 to 0)
    var query by mutableStateOf("")
    var letter by mutableStateOf<Char?>(null)
    var sort by mutableStateOf(LibrarySort.NEWEST_FIRST)

    val filtered: List<MangaItem>
        get() {
            val entries = LibraryFilter.apply(library.map { it.entry }, query, letter, sort)
            return entries.mapNotNull { e -> library.firstOrNull { it.entry.id == e.id } }
        }

    // ---------- манга ----------
    var currentManga by mutableStateOf<MangaItem?>(null)

    // ---------- читалка ----------
    var source: PageSource? = null
    var pageIndex by mutableStateOf(0)
    var pageCount by mutableStateOf(0)
    var currentChapter by mutableStateOf<ChapterEntry?>(null)
    var pageText by mutableStateOf<PageText?>(null)

    /**
     * Тексты всех страниц, распознанных в этой сессии.
     *
     * Раньше рамки рисовались только для текущей страницы, поэтому у прокрученных
     * страниц подсветка ИСЧЕЗАЛА — и нельзя было понять, какое облачко пропущено,
     * не подбирая момент прокруткой заново. Теперь рамки остаются на своих местах.
     */
    val pageTexts = mutableStateMapOf<Int, PageText>()

    /** Ключ реплики: страница + номер + начало текста. */
    fun replicaKey(page: Int, replica: Replica): String =
        "$page:${replica.index}:${replica.text.take(24)}"

    /** Реплики, которые уже прозвучали (рамка рисуется «прочитанной»). */
    val spokenKeys = mutableStateMapOf<String, Boolean>()

    fun isSpoken(page: Int, replica: Replica): Boolean = spokenKeys.containsKey(replicaKey(page, replica))

    /** Сколько реплик текущей страницы уже озвучено. */
    val spokenOnPage: Int get() = pageText?.replicas?.count { isSpoken(pageIndex, it) } ?: 0
    var recognizing by mutableStateOf(false)
    var showTextPanel by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var activeReplica by mutableStateOf(-1)
    /** Текст текущей озвучиваемой реплики (крупно в нижней полосе). */
    var spokenText by mutableStateOf("")
    /** Подпись движка озвучки, как в референсе: «Android TTS». */
    val ttsLabel: String = "Android TTS"
    var statusMessage by mutableStateOf<String?>(null)
    var modelsBusy by mutableStateOf(false)
    var modelsProgress by mutableStateOf("")
    var ttsReady by mutableStateOf(false)

    val replicaTotal: Int get() = pageText?.replicas?.size ?: 0

    /** Размер последней декодированной страницы (диагностика: видно в шапке читалки). */
    var pageBitmapInfo by mutableStateOf("")

    /** Пропорции текущей страницы (ширина/высота) — нужны для прокрутки к читаемой реплике. */
    var pageAspect by mutableStateOf(0.7f)

    /**
     * Где пользователь находится руками: страница под пальцем и доля её высоты (0…1).
     *
     * Раньше прокрутка никак не влияла на pageIndex: лента ехала, а OCR и ▶ работали
     * от старых координат — отсюда «сканирует от самого верха, хотя я в середине главы».
     */
    var visiblePage by mutableStateOf(-1)
        private set
    var visibleFraction by mutableStateOf(0f)
        private set

    /**
     * Лента сообщает, что видно на экране. Пока идёт озвучка или распознавание,
     * страницу НЕ переключаем (нельзя рвать чтение), но позицию запоминаем —
     * по ней выбирается реплика, с которой начинать.
     */
    fun onScrollSpot(page: Int, fractionInPage: Float) {
        visiblePage = page
        visibleFraction = fractionInPage.coerceIn(0f, 1f)
        if (speaking || recognizing) return
        if (page == pageIndex || page !in 0 until pageCount) return
        val manga = currentManga ?: return
        val chapter = currentChapter ?: return
        headSpokenKey = null
        pageIndex = page
        activeReplica = -1
        pageText = textStore.load(cacheKey(manga, chapter, page))?.withCacheFlag(true)
        pageText?.let { pageTexts[page] = it }
        prefetch(page + 1)
    }

    /**
     * Где на странице находится читаемая реплика: доля от высоты страницы (0…1).
     * По этому числу лента и постраничный режим подкручиваются к нужному облачку.
     */
    fun activeReplicaFraction(): Float? {
        val t = pageText ?: return null
        if (t.pageHeight <= 0) return null
        val r = t.replicas.firstOrNull { it.index == activeReplica } ?: return null
        return (r.box.top / t.pageHeight.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Настройки лежат в SharedPreferences, а Compose их НЕ наблюдает: переключатель
     * менял значение в prefs, но экран не перерисовывался, и рамки «не включались».
     * Поэтому флаги, которые видны в интерфейсе, живут ещё и в состоянии Compose.
     */
    var framesEnabled by mutableStateOf(true)
        private set

    var continuousMode by mutableStateOf(false)
        private set

    /** Прозрачность рамок (0.15…1.0) и их форма — Compose должен их видеть. */
    var frameOpacity by mutableStateOf(0.65f)
        private set
    var frameShape by mutableStateOf("rounded")
        private set

    /** Панель «Рамки»: прозрачность, форма, вкл/выкл. */
    var showFramesPanel by mutableStateOf(false)

    fun applyFrameOpacity(v: Float) {
        frameOpacity = v.coerceIn(0.15f, 1f)
        settings.frameOpacity = frameOpacity
    }

    fun applyFrameShape(shape: String) {
        frameShape = shape
        settings.frameShape = shape
        statusMessage = "Рамки: " + when (shape) {
            "ellipse" -> "эллипс"
            "circle" -> "круг"
            "lines" -> "по строкам"
            else -> "скруглённые"
        }
    }

    fun setFrames(on: Boolean) {
        framesEnabled = on
        settings.showTextFrames = on
        saveBookSettings()
    }

    fun setSpeed(value: Float) {
        settings.ttsSpeed = value
        saveBookSettings()
    }

    fun setContinuous(on: Boolean) {
        continuousMode = on
        settings.continuousScroll = on
        saveBookSettings()
    }

    /** Меню, скрытое на время озвучки; false — показано (или пользователь вызвал тапом). */
    var uiPeek by mutableStateOf(false)
        private set

    /** Скрывать ли панели прямо сейчас. */
    val uiHidden: Boolean get() = speaking && settings.hideUiWhileReading && !uiPeek

    fun showUiTemporarily() {
        uiPeek = true
    }

    fun hideUiAgain() {
        uiPeek = false
    }

    fun openJournal() {
        screen = Screen.Journal
    }

    /** Аудиожурнал: последние озвученные реплики, сохранённые в файлы. */
    fun audioJournalFiles(): List<java.io.File> = runCatching { tts.audioFiles() }.getOrDefault(emptyList())

    /** Аудиожурнал одним файлом: ZIP или его копия с .png (для загрузки в Arena). */
    fun audioArchive(asPng: Boolean): java.io.File? {
        val f = runCatching { tts.buildAudioArchive(asPng) }.getOrNull()
        statusMessage = when {
            f == null -> "Аудиожурнал пуст"
            asPng -> "Готово: ${f.name} (внутри ZIP со звуком)"
            else -> "Готово: ${f.name}"
        }
        return f
    }

    fun clearAudioJournal() {
        tts.clearAudioJournal()
        statusMessage = "Аудиожурнал очищен"
    }

    /** Настройки этой книги: применить при открытии, сохранить при изменении. */
    fun applyBookSettings() {
        val title = currentManga?.entry?.title ?: return
        val applied = settings.applyBook(title)
        // С этого момента любые изменения «книжных» настроек пишутся в профиль этой книги
        // автоматически (Settings.touchBook) — кнопка «Запомнить настройки книги» не нужна.
        settings.bindBook(title)
        framesEnabled = settings.showTextFrames
        frameOpacity = settings.frameOpacity
        frameShape = settings.frameShape
        continuousMode = settings.continuousScroll
        if (!applied && settings.perBookSettings) {
            // книга открыта впервые — запоминаем текущие настройки как её профиль
            settings.saveBook(title)
        }
        AppLog.d(
            "settings",
            "настройки книги «$title»: " +
                if (applied) "применены (привязка включена)" else "по умолчанию (запомнены)"
        )
    }

    fun saveBookSettings() {
        val title = currentManga?.entry?.title ?: return
        settings.saveBook(title)
    }

    init {
        AppLog.init(app)
        settings.migrateFramesOnce()
        settings.migrateEmptyDwellOnce()
        framesEnabled = settings.showTextFrames
        frameOpacity = settings.frameOpacity
        frameShape = settings.frameShape
        continuousMode = settings.continuousScroll
    }


    /** Сколько рамок можно нарисовать (диагностика: если 0 — подсвечивать нечего). */
    val frameCount: Int get() = pageText?.replicas?.count { it.box.area > 0f } ?: 0
    val bandTotal: Int get() = pageText?.bandCount ?: 0
    val currentReplicaNumber: Int get() = if (activeReplica < 0) 0 else activeReplica + 1
    val currentBandNumber: Int
        get() {
            val t = pageText ?: return 0
            val r = t.replicas.firstOrNull { it.index == activeReplica } ?: return 0
            return r.bandIndex + 1
        }
    val readProgress: Float
        get() {
            val total = replicaTotal
            if (total <= 0) return 0f
            return ((activeReplica + 1).toFloat() / total).coerceIn(0f, 1f)
        }

    private val pageCache = object : LruCache<Int, Bitmap>(settings.pageCacheSize.coerceAtLeast(1)) {}
    private var readJob: Job? = null
    private var script: List<TtsUtterance> = emptyList()

    /** Страница, к которой относится текущий script (нужна для ключей «прочитано»). */
    private var scriptPage: Int = 0

    /**
     * Ключ страницы, первая реплика которой УЖЕ озвучена вместе с предыдущей
     * (склейка через границу страниц, см. [CrossPage]). Когда эта страница станет
     * текущей, её первая реплика пропускается — иначе фраза прозвучит дважды.
     */
    private var headSpokenKey: String? = null

    /**
     * Сценарий озвучки страницы с учётом границ страниц:
     *  - если прошлая страница уже допела первую реплику этой — пропускаем её;
     *  - если последняя реплика не закончена, а следующая страница уже распознана
     *    (кеш/предзагрузка), хвост озвучивается вместе с её началом.
     */
    private fun scriptFor(text: PageText, index: Int): List<TtsUtterance> {
        val manga = currentManga
        val chapter = currentChapter
        val reps = text.replicas
        if (reps.isEmpty()) return emptyList()
        if (manga == null || chapter == null) return TtsScript.build(reps, settings.ttsSpeed)

        val key = cacheKey(manga, chapter, index)
        val skipFirst = headSpokenKey == key
        if (skipFirst) headSpokenKey = null

        var mergedTail: String? = null
        val prevRep = reps.lastOrNull()
        // на диск/в кеш за следующей страницей ходим ТОЛЬКО если хвост не закончен
        if (prevRep != null && prevRep.openEnded && index + 1 < pageCount) {
            val nextKey = cacheKey(manga, chapter, index + 1)
            val next = textStore.load(nextKey)
            val nextRep = next?.replicas?.firstOrNull { it.text.isNotBlank() }
            if (CrossPage.canJoin(prevRep, nextRep)) {
                mergedTail = CrossPage.join(prevRep!!.text, nextRep!!.text)
                headSpokenKey = nextKey
                AppLog.d(
                    "tts",
                    "склейка через страницу ${index + 1}→${index + 2}: " +
                        "«${prevRep.text}» + «${nextRep.text}»"
                )
            }
        }

        val prepared = reps.mapIndexed { i, r ->
            when {
                skipFirst && i == 0 -> r.copy(text = "")
                mergedTail != null && i == reps.lastIndex -> r.copy(text = mergedTail)
                else -> r
            }
        }.filter { it.text.isNotBlank() }
        return TtsScript.build(prepared, settings.ttsSpeed)
    }

    /** Пользователь явно попросил повторить реплику — одноразовый пропуск защиты. */
    private var forceRepeat: Boolean = false

    private fun utteranceKey(page: Int, item: TtsUtterance): String =
        "$page:${item.replicaIndex}:${item.text.take(24)}"

    init {
        // частотный словарь для восстановления пробелов (assets/ru_freq.txt, ~47 000 словоформ)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WordFrequencies.load(getApplication<Application>().assets) }
            // В журнале должно быть видно, ЧЕМ читали и загружен ли словарь: без этого
            // не понять, почему на телефоне не сработала починка слов.
            val ver = runCatching {
                val pi = getApplication<Application>().packageManager
                    .getPackageInfo(getApplication<Application>().packageName, 0)
                "${pi.versionName} (${pi.versionCode})"
            }.getOrDefault("?")
            val dict = if (WordFrequencies.isLoaded) "${WordFrequencies.size} словоформ" else "НЕ ЗАГРУЖЕН"
            if (WordFrequencies.isLoaded) runCatching { WordForms.ensureBuilt() }
            AppLog.d("log", "версия $ver · движок ${settings.localEngine} · словарь: $dict")
        }
        tts.init { ok ->
            ttsReady = ok
            if (!ok) statusMessage = "Системный TTS недоступен"
        }
        tts.onUtteranceDone = { id -> onUtteranceDone(id) }
        tts.onUtteranceStart = { id -> onUtteranceStart(id) }
        tts.onError = { msg -> statusMessage = msg }
    }

    // ---------- сканирование ----------

    fun chooseTree(uri: Uri) {
        settings.libraryTreeUri = uri.toString()
        scan()
    }

    fun scan() {
        if (settings.libraryTreeUri == null) return
        viewModelScope.launch {
            scanning = true
            scanProgress = 0 to 0
            val result = repo.scan { done, total, _ -> scanProgress = done to total }
            library = result
            scanning = false
        }
    }

    fun openManga(id: String) {
        currentManga = repo.find(id)
        screen = Screen.Manga(id)
    }

    fun openChapter(index: Int, page: Int = 0) {
        val manga = currentManga ?: return
        val chapter = manga.entry.chapters.getOrNull(index) ?: return
        viewModelScope.launch {
            closeSource()
            statusMessage = "Открываю «${chapter.info.displayName}»…"
            val result = repo.openChapter(manga, chapter)
            val opened = result.getOrNull()
            if (opened == null) {
                statusMessage = "Не удалось открыть «${chapter.name}»: " +
                    (result.exceptionOrNull()?.message ?: "неизвестная ошибка")
                return@launch
            }
            source = opened
            applyBookSettings()
            currentChapter = chapter
            pageCount = opened.pageCount
            pageIndex = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            pageText = textStore.load(cacheKey(manga, chapter, pageIndex))?.withCacheFlag(true)
            pageText?.let { pageTexts[pageIndex] = it }
            pageCache.evictAll()
            AppLog.d("reader", "открыта глава «${chapter.info.displayName}»: страниц $pageCount")
            statusMessage = if (pageCount > 0) null else "В главе не найдено страниц"
            screen = Screen.Reader(manga.entry.id, index, pageIndex)
            maybeAutoOcr()
        }
    }

    /**
     * Кеш текста привязан к версии движка: после смены моделей на PP-OCRv5 старый
     * распознанный текст подхватываться не должен — он собран прежним движком.
     */
    private fun cacheKey(manga: MangaItem, chapter: ChapterEntry, page: Int): String =
        "${manga.entry.title}/${chapter.name}/$page/${settings.localEngine}-ocr30"

    private fun closeSource() {
        stopReading()
        headSpokenKey = null
        runCatching { source?.close() }
        source = null
        pageCache.evictAll()
    }

    // ---------- страницы ----------

    fun readingMode(): ReadingMode = when (currentManga?.entry?.readingMode) {
        lv.bibliogid.manga.core.library.ReadingModeHint.WEBTOON_VERTICAL -> ReadingMode.WEBTOON_VERTICAL
        lv.bibliogid.manga.core.library.ReadingModeHint.COMIC_LTR -> ReadingMode.COMIC_LTR
        else -> runCatching { ReadingMode.valueOf(settings.readMode) }.getOrDefault(ReadingMode.MANGA_RTL)
    }

    /** Последняя ошибка декодирования страницы (показывается в читалке). */
    var pageError by mutableStateOf<String?>(null)

    suspend fun pageBitmap(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        pageCache.get(index)?.let { return@withContext it }
        val src = source ?: run {
            pageError = "глава не открыта"
            return@withContext null
        }
        val result = runCatching { src.decodePage(index, width) }
        val bitmap = result.getOrNull()
        if (bitmap == null) {
            val reason = result.exceptionOrNull()?.message
            val pageName = src.pageNames().getOrNull(index) ?: "страница ${index + 1}"
            pageError = buildString {
                append("не декодировано: $pageName")
                if (!reason.isNullOrBlank()) append(" · $reason")
            }
            android.util.Log.e("Reader", "decode page $index failed: $reason")
            return@withContext null
        }
        pageError = null
        pageCache.put(index, bitmap)
        pageBitmapInfo = "${bitmap.width}×${bitmap.height}"
        if (index == pageIndex && bitmap.height > 0) pageAspect = bitmap.width.toFloat() / bitmap.height
        bitmap
    }

    /** Подгрузка соседней страницы заранее — чтобы листание было плавным. */
    fun prefetch(index: Int) {
        if (index < 0 || index >= pageCount) return
        if (pageCache.get(index) != null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { source?.decodePage(index, 1200) }?.getOrNull()?.let { pageCache.put(index, it) }
        }
    }

    fun goToPage(index: Int) {
        if (index == pageIndex) return
        // ручной прыжок: склейка «хвост прошлой страницы + начало этой» больше не действует
        headSpokenKey = null
        pageIndex = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val manga = currentManga
        val chapter = currentChapter
        if (manga != null && chapter != null) {
            pageText = textStore.load(cacheKey(manga, chapter, pageIndex))?.withCacheFlag(true)
            pageText?.let { pageTexts[pageIndex] = it }
        }
        activeReplica = -1
        if (speaking) {
            // Продолжаем с первой НЕозвученной реплики этой страницы. Раньше здесь
            // был полный перезапуск с реплики 1 — отсюда и «он повторяет реплики»:
            // прокрутка ленты дёргала goToPage и чтение откатывалось назад.
            resumeOnPage()
        } else {
            maybeAutoOcr()
        }
    }

    /** Авто-распознавание открытой страницы (настройка «распознавать сразу»). */
    private fun maybeAutoOcr() {
        // текст есть, но без координат (старый кеш) — рамки рисовать нечем, распознаём заново
        val needsBoxes = pageText != null &&
            pageText!!.replicas.none { it.box.area > 0f } &&
            pageText!!.replicas.any { it.text.isNotBlank() }
        if (!settings.autoOcr && !needsBoxes) return
        if (!needsBoxes && pageText?.replicas?.any { it.text.isNotBlank() } == true) return
        viewModelScope.launch {
            delay(350)          // сначала даём странице отрисоваться
            if (!recognizing) recognizePage()
        }
    }

    // ---------- OCR ----------

    fun currentEngine(): OcrEngine? {
        if (settings.useOnline) {
            return when (settings.onlineEngine) {
                "yandex" -> if (settings.apiKeyYandex.isNotBlank() && settings.folderIdYandex.isNotBlank())
                    YandexOcrEngine(settings.apiKeyYandex, settings.folderIdYandex) else null
                "google" -> if (settings.apiKeyGoogle.isNotBlank()) GoogleVisionOcrEngine(settings.apiKeyGoogle) else null
                "ocrspace" -> if (settings.apiKeyOcrSpace.isNotBlank()) OcrSpaceEngine(settings.apiKeyOcrSpace) else null
                else -> null
            }
        }
        return when (settings.localEngine) {
            "paddle" -> {
                if (models.isReady) PaddleOcrEngine(models.detFile, models.recFile, models.dictFile) else null
            }
            else -> MlKitOcrEngine()
        }
    }

    fun engineStatusText(): String = when {
        settings.useOnline -> {
            val keyOk = when (settings.onlineEngine) {
                "yandex" -> settings.apiKeyYandex.isNotBlank()
                "google" -> settings.apiKeyGoogle.isNotBlank()
                else -> settings.apiKeyOcrSpace.isNotBlank()
            }
            if (keyOk) "облако · ${settings.onlineEngine}" else "облако · нет ключа API"
        }
        settings.localEngine == "paddle" ->
            if (models.isReady) "локально · PaddleOCR (кириллица)" else "локально · модель не скачана"
        else -> "локально · ML Kit"
    }

    fun downloadModels() {
        if (modelsBusy) return
        viewModelScope.launch {
            modelsBusy = true
            modelsProgress = "0%"
            val ok = models.ensureModels { file, done, total ->
                val pct = if (total > 0) (done * 100 / total) else 0
                modelsProgress = "$file · $pct%"
            }
            modelsBusy = false
            modelsProgress = if (ok) "модели готовы" else "ошибка загрузки"
            statusMessage = if (ok) "Модели OCR готовы (${models.sizeBytes() / 1024 / 1024} МБ)"
            else "Ошибка загрузки моделей: ${models.lastError ?: "нет сети?"}"
        }
    }

    /** Ручной импорт моделей: пользователь выбирает папку с det.onnx, rec.onnx, dict.txt. */
    fun importModels(treeUri: Uri) {
        viewModelScope.launch {
            modelsBusy = true
            modelsProgress = "импорт…"
            val children = storage.listChildrenOfDocument(treeUri).filter { !it.isDirectory }
            val wanted = listOf("det.onnx", "rec.onnx", "dict.txt")
            val streams = HashMap<String, java.io.InputStream>()
            for (name in wanted) {
                val node = children.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
                storage.openStream(node.uri)?.let { streams[name] = it }
            }
            val ok = if (streams.size == 3) models.importFromFolder(streams) else false
            streams.values.forEach { runCatching { it.close() } }
            modelsBusy = false
            statusMessage = if (ok) "Модели импортированы" else
                "Импорт не удался: нужны файлы det.onnx, rec.onnx, dict.txt (найдено ${streams.size}/3)"
        }
    }

    fun deleteModels() {
        viewModelScope.launch {
            models.deleteAll()
            statusMessage = "Модели удалены"
        }
    }

    fun recognizePage(fullPage: Boolean = false) {
        val manga = currentManga ?: return
        val chapter = currentChapter ?: return
        if (recognizing) return
        viewModelScope.launch {
            recognizing = true
            val width = 1560
            val bitmap = pageBitmap(pageIndex, width)
            val engine = currentEngine()
            if (bitmap == null || engine == null) {
                recognizing = false
                statusMessage = when {
                    bitmap == null -> "Страница не декодирована"
                    settings.useOnline -> "Нужен ключ API в настройках"
                    else -> "Скачайте модель OCR в настройках"
                }
                return@launch
            }
            val pipeline = OcrPipeline()
            val text = pipeline.process(bitmap, engine, readingMode(), fullPage)
            engine.close()
            pageText = text
            pageTexts[pageIndex] = text
            AppLog.d(
                "ocr",
                "стр.${pageIndex + 1}/$pageCount ${bitmap.width}x${bitmap.height}: " +
                    "реплик ${text.replicas.size}, полос ${text.bandCount}, ${text.elapsedMs} мс, " +
                    "строк ${text.rawLineCount}" +
                (if (text.silenceCount > 0) ", молчание ${text.silenceCount}" else "") +
                (if (text.warnings.isEmpty()) "" else " · ${text.warnings.joinToString("; ")}")
            )
            text.replicas.forEach { r ->
                AppLog.d(
                    "text",
                    "${r.index + 1}. [${r.box.left.toInt()},${r.box.top.toInt()}," +
                        "${r.box.right.toInt()},${r.box.bottom.toInt()}] строк ${r.lines.size}: ${r.text}"
                )
            }
            textStore.save(cacheKey(manga, chapter, pageIndex), text, book = manga.entry.title)
            recognizing = false
            if (text.replicas.none { it.text.isNotBlank() }) statusMessage = "Текст не найден"
            val wasSpeaking = speaking
            if (speaking) stopReading()
            when {
                pendingRead -> { pendingRead = false; startReading(allowReplay = false) }
                wasSpeaking -> startReading(allowReplay = false)
            }
        }
    }

    /** «Распознать заново» — игнорируем кеш и гоняем OCR по странице. */
    fun recognizeAgain(fullPage: Boolean = false) {
        val manga = currentManga
        val chapter = currentChapter
        if (manga != null && chapter != null) {
            textStore.forgetPage(cacheKey(manga, chapter, pageIndex))
        }
        statusMessage = "Распознаю страницу заново…"
        recognizePage(fullPage)
    }

    /** «Забыть текст этой книги». */
    fun forgetThisBook() {
        val manga = currentManga ?: return
        val removed = textStore.forgetBook(manga.entry.title)
        pageText = null
        pageTexts.clear()
        spokenKeys.clear()
        statusMessage = "Забыт текст книги «${manga.entry.title}» (файлов: $removed)"
    }

    /** «Сбросить у всех книг». */
    fun forgetAllBooks() {
        val removed = textStore.clearAll()
        pageText = null
        pageTexts.clear()
        spokenKeys.clear()
        statusMessage = "Сброшен текст всех книг (файлов: $removed)"
    }

    // ---------- авто-чтение ----------

    fun toggleReading() {
        when {
            !speaking -> startReading(fromSpot = true)
            paused -> resumeReading()
            else -> pauseReading()
        }
    }

    fun startReading(fromIndex: Int = 0, allowReplay: Boolean = true, fromSpot: Boolean = false) {
        val text = pageText
        if (text == null || text.replicas.none { it.text.isNotBlank() }) {
            // голос не может звучать без текста: сначала распознаём страницу, потом читаем
            pendingRead = true
            if (recognizing) statusMessage = "Распознаю текст страницы…"
            else recognizePage()
            return
        }
        if (!ttsReady) statusMessage = "Системный TTS ещё не готов — повторите через секунду"
        stopReadingInternal()
        speaking = true
        paused = false
        scriptPage = pageIndex
        script = scriptFor(text, pageIndex)
        if (script.isEmpty()) {
            speaking = false
            statusMessage = "Нечего читать: на странице нет распознанного текста"
            return
        }
        // Пользователь сам нажал ▶, а на странице всё уже прозвучало — читаем заново
        if (allowReplay && script.all { spokenKeys.containsKey(utteranceKey(scriptPage, it)) }) {
            text.replicas.forEach { spokenKeys.remove(replicaKey(pageIndex, it)) }
            AppLog.d("tts", "стр.${pageIndex + 1}: всё прочитано — начинаю страницу заново по кнопке")
        }
        // ▶ после прокрутки читает с того места, которое видно, а не с верха страницы
        val startIndex = if (fromSpot && fromIndex == 0) {
            val startReplica = startReplicaAtSpot(text)
            val at = script.indexOfFirst { it.replicaIndex >= startReplica }
            if (at < 0) fromIndex else at
        } else fromIndex
        playIndex = startIndex.coerceIn(0, script.size - 1)
        activeReplica = script[playIndex].replicaIndex
        spokenText = script[playIndex].text
        if (fromSpot && playIndex > 0) {
            AppLog.d(
                "tts",
                "стр.${pageIndex + 1}: начинаю с реплики ${activeReplica + 1} " +
                    "(видно ${(visibleFraction * 100).toInt()} % страницы)"
            )
        }
        readJob = viewModelScope.launch { runFrom(playIndex) }
        prefetchText(pageIndex + 1)
    }

    /**
     * Номер реплики, на которой сейчас «стоит» пользователь: первая реплика,
     * чей центр ниже видимой доли страницы. Если он в самом верху — 0.
     */
    private fun startReplicaAtSpot(text: PageText): Int {
        if (text.replicas.isEmpty()) return 0
        if (text.pageHeight <= 0) return 0
        if (visiblePage != pageIndex) return 0
        val frac = visibleFraction
        if (frac <= 0.02f) return 0
        val spot = frac * text.pageHeight
        val idx = text.replicas.indexOfFirst { it.box.centerY >= spot }
        return if (idx < 0) 0 else idx
    }

    private suspend fun runFrom(startIndex: Int) {
        var i = startIndex
        playIndex = i
        while (speaking && i < script.size) {
            playIndex = i
            val item = script[i]
            val key = utteranceKey(scriptPage, item)
            if (!forceRepeat && spokenKeys.containsKey(key)) {
                // Никогда не читаем одно и то же дважды: после прокрутки лента
                // возвращалась на страницу и чтение начиналось с её первой реплики.
                AppLog.d(
                    "tts",
                    "стр.${scriptPage + 1} реплика ${item.replicaIndex + 1}: уже озвучена — пропускаю"
                )
                i++
                continue
            }
            forceRepeat = false
            activeReplica = item.replicaIndex
            spokenText = item.text
            val spoken = SpokenText.prepare(item.text)
            AppLog.d(
                "tts",
                "стр.${pageIndex + 1} реплика ${item.replicaIndex + 1}: ${item.text}" +
                    if (spoken != item.text) "  ->  голосом: $spoken" else ""
            )
            speak(item, spoken)
            val ok = waitForUtterance(item.replicaIndex)
            if (!speaking) return
            if (!ok) return
            spokenKeys[key] = true
            // Предложение продолжается (нет точки/!/?) — не делаем полную паузу,
            // иначе речь рвётся «по переходам» вместо цельной фразы.
            val t = item.text.trimEnd()
            val continues = !(t.endsWith(".") || t.endsWith("!") || t.endsWith("?") ||
                t.endsWith("…") || t.endsWith("»") || t.endsWith("\""))
            delay(if (continues) Pacing.gapMs(settings.ttsSpeed) / 3 else Pacing.gapMs(settings.ttsSpeed))
            if (!speaking) return
            i++
        }
        if (speaking && i >= script.size) {
            delay(Pacing.pageTurnDelayMs(settings.ttsSpeed))
            advanceToNextPage()
        }
    }

    /**
     * Автоперелистывание БЕЗ рывков.
     *
     * Раньше здесь вызывался goToPageNext(), который внутри делал stopReading() —
     * то есть корутина отменяла сама себя, и чтение обрывалось на первой же странице.
     * Теперь текст следующей страницы готовится заранее (кеш или OCR), страница
     * переключается молча, и чтение продолжается тем же потоком.
     */
    private fun advanceToNextPage() {
        if (!speaking) return
        if (!settings.ttsAutoPageTurn) { stopReading(); return }
        if (pageIndex >= pageCount - 1) {
            stopReading()
            statusMessage = "Глава дочитана"
            return
        }
        val job = viewModelScope.launch {
            val next = pageIndex + 1
            statusMessage = "Готовлю страницу ${next + 1}…"
            val text = preparedText(next)
            if (!speaking) return@launch
            if (text == null || text.replicas.none { it.text.isNotBlank() }) {
                // страница без текста — это часть сюжета (рисунок, пейзаж, немая сцена).
                // Не пролетаем за 0.9 с: держим паузу из настроек, чтобы не рвать историю.
                switchPageSilently(next, text)
                statusMessage = null
                delay(emptyPageDwellMs())
                advanceToNextPage()
                return@launch
            }
            switchPageSilently(next, text)
            statusMessage = null
            scriptPage = next
            script = scriptFor(text, next)
            if (script.isEmpty()) { advanceToNextPage(); return@launch }
            playIndex = 0
            runFrom(0)
        }
        readJob = job
    }

    /** Пауза на странице без текста: настройка пользователя, но не меньше 1.5 с. */
    fun emptyPageDwellMs(): Long {
        val sec = settings.emptyPageDwellSec.coerceIn(1.0f, 15.0f)
        return (sec * 1000L).toLong()
    }

    /** Текст страницы: из кеша, иначе распознаём (без изменения экрана). */
    private suspend fun preparedText(index: Int): PageText? {
        val manga = currentManga ?: return null
        val chapter = currentChapter ?: return null
        val key = cacheKey(manga, chapter, index)
        textStore.load(key)?.let { cached ->
            if (cached.replicas.any { it.text.isNotBlank() }) {
                pageTexts[index] = cached
                logPageText(index, cached, cached = true)
                return cached.withCacheFlag(true)
            }
        }
        val bitmap = pageBitmap(index, 1560) ?: return null
        val engine = currentEngine() ?: return null
        val pipeline = OcrPipeline()
        val text = withContext(Dispatchers.Default) { pipeline.process(bitmap, engine, readingMode(), false) }
        engine.close()
        textStore.save(key, text, book = manga.entry.title)
        pageTexts[index] = text
        logPageText(index, text, cached = false, width = bitmap.width, height = bitmap.height)
        return text
    }

    /**
     * Одна и та же запись в журнал для ЛЮБОГО пути распознавания (кнопка «Распознать» и
     * фоновое распознавание во время чтения). Раньше путь чтения молчал, и в журнале не
     * было ни строк [ocr], ни координат рамок — по такому журналу ничего не проверить.
     */
    private fun logPageText(index: Int, text: PageText, cached: Boolean, width: Int = 0, height: Int = 0) {
        AppLog.d(
            "ocr",
            "стр.${index + 1}/$pageCount" + (if (width > 0) " ${width}x${height}" else "") +
                (if (cached) " (из кеша)" else "") +
                ": реплик ${text.replicas.size}, полос ${text.bandCount}, ${text.elapsedMs} мс, " +
                "строк ${text.rawLineCount}" +
                (if (text.silenceCount > 0) ", молчание ${text.silenceCount}" else "") +
                (if (text.warnings.isEmpty()) "" else " · ${text.warnings.joinToString("; ")}")
        )
        if (cached) return
        text.replicas.forEach { r ->
            AppLog.d(
                "text",
                "${r.index + 1}. [${r.box.left.toInt()},${r.box.top.toInt()}," +
                    "${r.box.right.toInt()},${r.box.bottom.toInt()}] строк ${r.lines.size}: ${r.text}"
            )
        }
    }

    /** Переключение страницы без остановки озвучки. */
    private fun switchPageSilently(index: Int, text: PageText?) {
        pageIndex = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pageText = text
        if (text != null) pageTexts[pageIndex] = text
        activeReplica = -1
        prefetch(pageIndex + 1)
        prefetchText(pageIndex + 1)
    }

    /** Фоновое распознавание следующей страницы, пока читается текущая. */
    private fun prefetchText(index: Int) {
        if (index < 0 || index >= pageCount) return
        if (prefetchingTextPage == index) return
        val manga = currentManga ?: return
        val chapter = currentChapter ?: return
        val key = cacheKey(manga, chapter, index)
        if (textStore.load(key)?.replicas?.any { it.text.isNotBlank() } == true) return
        prefetchingTextPage = index
        viewModelScope.launch {
            runCatching { preparedText(index) }
            if (prefetchingTextPage == index) prefetchingTextPage = -1
        }
    }

    private var prefetchingTextPage: Int = -1

    /** ⏸ — останавливаем озвучку, но остаёмся на текущей реплике. */
    fun pauseReading() {
        if (!speaking) return
        paused = true
        tts.stop()
        readJob?.cancel()
        readEffectJob?.cancel()
        readJob = null
    }

    /** ▶ — продолжаем с текущей реплики. */
    fun resumeReading() {
        if (!speaking) { startReading(); return }
        paused = false
        val from = playIndex.coerceIn(0, (script.size - 1).coerceAtLeast(0))
        readJob = viewModelScope.launch { runFrom(from) }
    }

    /**
     * Продолжить чтение после перехода на другую страницу (прокрутка ленты,
     * листание) — с первой неозвученной реплики этой страницы.
     */
    private fun resumeOnPage() {
        stopReadingInternal()
        val text = pageText
        if (text == null || text.replicas.none { it.text.isNotBlank() }) {
            maybeAutoOcr()
            return
        }
        scriptPage = pageIndex
        script = scriptFor(text, pageIndex)
        val from = script.indexOfFirst { !spokenKeys.containsKey(utteranceKey(scriptPage, it)) }
        if (from < 0) {
            AppLog.d("tts", "стр.${pageIndex + 1}: все реплики уже озвучены — повтор не начинаю")
            return
        }
        playIndex = from
        activeReplica = script[from].replicaIndex
        spokenText = script[from].text
        AppLog.d(
            "tts",
            "стр.${pageIndex + 1}: продолжаю с реплики ${script[from].replicaIndex + 1} " +
                "(пропущено уже озвученных: $from)"
        )
        readJob = viewModelScope.launch { runFrom(from) }
    }

    /** ⏭ следующая реплика. */
    fun nextReplica() {
        if (script.isEmpty()) return
        val from = (playIndex + 1).coerceAtMost(script.size - 1)
        readEffectJob?.cancel()
        if (speaking) {
            paused = false
            readJob = viewModelScope.launch { runFrom(from) }
        } else {
            startReading(from)
        }
    }

    /** ↺ повторить текущую реплику. */
    fun repeatReplica() {
        if (script.isEmpty()) return
        forceRepeat = true
        val from = playIndex.coerceIn(0, script.size - 1)
        readEffectJob?.cancel()
        if (speaking) {
            paused = false
            readJob = viewModelScope.launch { runFrom(from) }
        } else {
            startReading(from)
        }
    }

    /** Перейти к конкретной реплике (тап по строке в панели текста). */
    fun speakReplica(replicaIndex: Int) {
        forceRepeat = true
        val idx = script.indexOfFirst { it.replicaIndex == replicaIndex }
        if (idx >= 0) {
            paused = false
            speaking = true
            readJob = viewModelScope.launch { runFrom(idx) }
        } else {
            startReading()
        }
    }

    private val doneEvents = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val startedEvents = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private var readEffectJob: Job? = null

    private fun speak(item: TtsUtterance, spoken: String = item.text) {
        doneEvents.remove(item.replicaIndex)
        startedEvents.remove(item.replicaIndex)
        if (item.text.isBlank()) return
        tts.speak(if (spoken.isBlank()) item.text else spoken, settings.ttsSpeed, "r${item.replicaIndex}")
    }

    private fun onUtteranceStart(id: String) {
        val index = id.removePrefix("r").toIntOrNull() ?: return
        startedEvents[index] = true
    }

    private suspend fun waitForUtterance(replica: Int): Boolean {
        var waited = 0
        val estimate = Pacing.replicaDurationMs(
            script.firstOrNull { it.replicaIndex == replica }?.text?.length ?: 0,
            settings.ttsSpeed
        )
        while (speaking && !paused) {
            if (doneEvents.remove(replica) != null) return true
            // «закончилось» считаем только если движок реально начал говорить:
            // иначе первый же тик 60 мс обрывал реплику, и голоса не было слышно
            // «движок молчит» считаем концом реплики только когда расчётное время уже прошло:
            // иначе любой сбой колбэка (например, от записи аудиожурнала) обрывал чтение
            if (startedEvents.containsKey(replica) && !tts.isSpeaking && waited > estimate * 0.5f) return true
            // движок не сказал ни слова (нет голоса/ошибка) — не зацикливаемся
            if (!startedEvents.containsKey(replica) && waited > 8000) return true
            delay(60)
            waited += 60
            if (waited > estimate + 20000) return true
        }
        return false
    }

    private fun onUtteranceDone(id: String) {
        val index = id.removePrefix("r").toIntOrNull() ?: return
        doneEvents[index] = true
    }

    private var playIndex: Int = 0

    /** Пользователь нажал «Прочитать»/🎤 до распознавания: как только текст будет — читаем. */
    private var pendingRead: Boolean = false

    fun stopReading() {
        stopReadingInternal()
        speaking = false
        paused = false
        activeReplica = -1
        spokenText = ""
        playIndex = 0
    }

    private fun stopReadingInternal() {
        readJob?.cancel()
        readJob = null
        readEffectJob?.cancel()
        readEffectJob = null
        tts.stop()
    }

    /** Страница вообще без текста: не «пролетаем», а держим фиксированную паузу. */
    suspend fun emptyPageDelayMs(): Long = Pacing.emptyPageDwellMs(settings.ttsSpeed)

    fun goToPageNext() {
        if (pageIndex < pageCount - 1) goToPage(pageIndex + 1)
    }

    fun goToPagePrev() {
        if (pageIndex > 0) goToPage(pageIndex - 1)
    }

    fun back() {
        screen = when (val s = screen) {
            is Screen.Reader -> {
                stopReading()
                closeSource()
                Screen.Manga(s.mangaId)
            }
            is Screen.Manga -> {
                currentManga = null
                Screen.Library
            }
            else -> Screen.Library
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopReading()
        closeSource()
        tts.shutdown()
    }
}
