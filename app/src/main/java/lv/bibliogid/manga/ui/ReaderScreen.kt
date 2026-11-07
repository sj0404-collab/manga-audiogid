package lv.bibliogid.manga.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lv.bibliogid.manga.core.ocr.Box
import lv.bibliogid.manga.core.ocr.ReadingMode
import lv.bibliogid.manga.core.ocr.RegionType

@Composable
fun ReaderScreen(vm: AppViewModel) {
    val manga = vm.currentManga ?: return
    val chapter = vm.currentChapter ?: return
    val continuous = vm.continuousMode
    val mode = vm.readingMode()

    // Меню прячется, пока идёт озвучка: тап возвращает его на 3 секунды.
    val uiHidden = vm.uiHidden
    LaunchedEffect(vm.speaking, vm.activeReplica, vm.uiPeek) {
        if (vm.speaking && vm.uiPeek) {
            kotlinx.coroutines.delay(3000)
            vm.hideUiAgain()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { vm.showUiTemporarily() }
            }
    ) {
        // Нижние панели перекрывают страницу — оставляем под них место,
        // иначе нижнее облачко с текстом оказывается под плеером.
        val bottomInset = when {
            uiHidden -> 8.dp
            vm.speaking -> 210.dp
            else -> 104.dp
        }
        Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
        if (continuous) {
            ContinuousReader(vm)
        } else {
            val pagerState = rememberPagerState(initialPage = vm.pageIndex, pageCount = { vm.pageCount })
            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage != vm.pageIndex) vm.goToPage(pagerState.currentPage)
            }
            LaunchedEffect(vm.pageIndex) {
                if (pagerState.currentPage != vm.pageIndex) pagerState.animateScrollToPage(vm.pageIndex)
            }
            HorizontalPager(
                state = pagerState,
                reverseLayout = mode == ReadingMode.MANGA_RTL,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                PageView(vm, page)
            }
        }
        }

        // верхняя панель
        if (!uiHidden) Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Color.White, fontSize = 22.sp, modifier = Modifier.clickable { vm.back() })
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(chapter.info.displayName, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    "${vm.pageIndex + 1} / ${vm.pageCount}  ·  " +
                        (if (vm.pageBitmapInfo.isNotBlank()) "${vm.pageBitmapInfo}  ·  " else "") +
                        vm.engineStatusText(),
                    color = Color(0xFFB9C3D4),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            ToolbarIcon(if (continuous) "⇅" else "⇄") {
                vm.setContinuous(!continuous)
            }
            ToolbarIcon("🔍") { vm.showTextPanel = true }
            ToolbarIcon("🎤") { if (vm.speaking) vm.toggleReading() else vm.startReading() }
            FrameToggle(vm)
            ToolbarIcon("Aa") { vm.showTextPanel = true }
            ToolbarIcon("≡") { vm.openJournal() }
        }

        // нижняя панель управления
        if (!uiHidden) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .navigationBarsPadding()
                .padding(10.dp)
        ) {
            if (vm.speaking) {
                SpeechPlayer(vm)
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlButton("◀") { vm.goToPagePrev() }
                Spacer(Modifier.width(8.dp))
                ControlButton(if (vm.speaking) "⏸" else "▶") { vm.toggleReading() }
                Spacer(Modifier.width(8.dp))
                ControlButton("▶|") { vm.goToPageNext() }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Текст: ${vm.pageText?.replicas?.size ?: 0} репл." +
                        (if (vm.spokenOnPage > 0) " · прочитано ${vm.spokenOnPage}" else "") +
                        (if ((vm.pageText?.silenceCount ?: 0) > 0) " · молчание ${vm.pageText!!.silenceCount}" else ""),
                    color = Color(0xFFB9C3D4),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                ControlButton(if (vm.recognizing) "…" else "OCR") { vm.recognizePage() }
                Spacer(Modifier.width(6.dp))
                ControlButton("≡") { vm.openJournal() }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("скорость", color = Color(0xFFB9C3D4), style = MaterialTheme.typography.labelSmall)
                Text(
                    "−",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clickable { vm.setSpeed((vm.settings.ttsSpeed - 0.25f).coerceAtLeast(0.25f)) }
                        .padding(horizontal = 6.dp)
                )
                Slider(
                    value = vm.settings.ttsSpeed,
                    onValueChange = { vm.setSpeed(it) },
                    valueRange = 0.25f..2.0f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    "＋",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clickable { vm.setSpeed((vm.settings.ttsSpeed + 0.25f).coerceAtMost(2.0f)) }
                        .padding(horizontal = 6.dp)
                )
                Text(
                    String.format("%.2fx", vm.settings.ttsSpeed),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            LinearProgressIndicator(
                progress = { if (vm.pageCount > 0) (vm.pageIndex + 1f) / vm.pageCount else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        }

        if (vm.showTextPanel) {
            PageTextSheet(vm)
        }
    }
}

/**
 * Переключатель рамок. Раньше это была иконка ▢/▣ — два почти одинаковых квадратика,
 * состояние по ним не читалось, и рамки выключали случайно. Теперь это подпись со
 * состоянием: «рамки 14» / «рамки выкл».
 */
@Composable
private fun FrameToggle(vm: AppViewModel) {
    val on = vm.framesEnabled
    val count = vm.frameCount
    Surface(
        color = if (on) Color(0xFF0E7490) else Color(0x33FFFFFF),
        shape = RoundedCornerShape(8.dp),
        onClick = {
            vm.setFrames(!on)
            vm.statusMessage = if (!on) "Рамки включены: $count шт." else "Рамки выключены"
        }
    ) {
        Text(
            text = if (on) "рамки $count" else "рамки выкл",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ToolbarIcon(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 17.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * Полоса плеера озвучки как в референсе:
 * «реплика 7 из 12 · полоса 2 из 3 · Android TTS», крупный текст текущей реплики,
 * пауза / следующая / повтор / стоп и прогресс чтения страницы.
 */
@Composable
private fun SpeechPlayer(vm: AppViewModel) {
    val total = vm.replicaTotal
    val band = vm.bandTotal
    val number = vm.currentReplicaNumber.coerceAtLeast(1)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF14161B), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(
            "реплика $number из $total · полоса ${vm.currentBandNumber.coerceAtLeast(1)} из $band · " +
                "${vm.ttsLabel} · рамок ${vm.frameCount}",
            color = Color(0xFF8FA3BF),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
        Text(
            vm.spokenText.ifBlank { "текст реплики недоступен" },
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerButton(if (vm.paused) "▶" else "⏸") { vm.toggleReading() }
            PlayerButton("⏭") { vm.nextReplica() }
            PlayerButton("↺") { vm.repeatReplica() }
            PlayerButton("⏹") { vm.stopReading() }
            LinearProgressIndicator(
                progress = { vm.readProgress },
                modifier = Modifier.weight(1f).height(4.dp)
            )
        }
    }
}

@Composable
private fun PlayerButton(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = androidx.compose.foundation.shape.CircleShape,
        onClick = onClick
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
        onClick = onClick
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PageView(vm: AppViewModel, index: Int, fillWidth: Boolean = false) {
    var bitmap by remember(index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(index) { mutableStateOf<String?>(null) }
    var attempt by remember(index) { mutableStateOf(0) }

    LaunchedEffect(index, attempt) {
        if (bitmap != null) return@LaunchedEffect
        error = null
        val loaded = runCatching { vm.pageBitmap(index, 1400) }.getOrNull()
        bitmap = loaded
        if (loaded == null) error = vm.pageError ?: "страница не декодировалась"
        vm.prefetch(index + 1)
    }

    val bmp = bitmap
    val err = error

    if (bmp != null) {
        // Пропорции картинки. Никакого «подгонять всё под экран»: вебтун-страница
        // должна быть шириной с экран и листаться вертикально, иначе она превращается
        // в невидимую полоску.
        val aspect = if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 0.7f
        // Сильно вытянутая страница (вебтун) в постраничном режиме: подгонять её под
        // высоту экрана нельзя — получится нечитаемая полоска. Показываем на всю ширину
        // с вертикальной прокруткой.
        val tall = aspect < 0.55f
        if (fillWidth) {
            val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
            Box(Modifier.fillMaxWidth().height(screenW / aspect)) {
                PageContent(vm, index, bmp, Modifier.fillMaxSize())
            }
        } else if (tall) {
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            val density = androidx.compose.ui.platform.LocalDensity.current
            val screenW = cfg.screenWidthDp.dp
            val pageHPx = with(density) { (screenW / aspect).toPx() }
            val viewportPx = with(density) { cfg.screenHeightDp.dp.toPx() }
            val scroll = rememberScrollState()
            LaunchedEffect(vm.activeReplica, index, vm.speaking) {
                if (!vm.speaking || index != vm.pageIndex) return@LaunchedEffect
                val fraction = vm.activeReplicaFraction() ?: return@LaunchedEffect
                val target = (fraction * pageHPx - viewportPx / 3f).toInt()
                scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
            }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().height(screenW / aspect)) {
                    PageContent(vm, index, bmp, Modifier.fillMaxSize())
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val areaW = maxWidth
                val areaH = maxHeight
                val areaAspect = if (areaH.value > 0f) areaW.value / areaH.value else 1f
                val imgW = if (aspect > areaAspect) areaW else areaH * aspect
                val imgH = if (aspect > areaAspect) areaW / aspect else areaH
                Box(Modifier.width(imgW).height(imgH)) {
                    PageContent(vm, index, bmp, Modifier.fillMaxSize())
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (err != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text("Страница не открылась", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        err,
                        color = Color(0xFFB9C3D4),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { attempt++ }) { Text("Повторить") }
                }
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

/**
 * Картинка страницы + рамки распознанного текста.
 * Рамки считаются в НОРМИРОВАННЫХ координатах (0..1) от размера самой картинки,
 * поэтому они не могут разъехаться с изображением ни при каком масштабе.
 */
@Composable
private fun PageContent(
    vm: AppViewModel,
    index: Int,
    bmp: android.graphics.Bitmap,
    modifier: Modifier = Modifier
) {
    // Текст берём из карты страниц: прокрученная страница тоже остаётся с рамками,
    // иначе нельзя понять, какое облачко уже прочитано, а какое пропущено.
    val text = vm.pageTexts[index] ?: (if (index == vm.pageIndex) vm.pageText else null)
    val showFrames = vm.framesEnabled && text != null
    val frames = if (showFrames) text!!.replicas.filter { it.box.area > 0f } else emptyList()
    val refW = if (showFrames && text!!.pageWidth > 0) text.pageWidth.toFloat() else bmp.width.toFloat()
    val refH = if (showFrames && text!!.pageHeight > 0) text.pageHeight.toFloat() else bmp.height.toFloat()
    val active = vm.activeReplica
    val livePage = index == vm.pageIndex

    Box(modifier) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Страница ${index + 1}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        if (frames.isNotEmpty() && refW > 0f && refH > 0f) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val padX = 5.dp.toPx()
                val padY = 6.dp.toPx()
                val badgePaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.WHITE
                    textSize = 11.dp.toPx()
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                }
                frames.forEach { r ->
                    // Рамка = регион, доращенный до контура облачка в OcrPipeline.
                    // Раньше здесь брался union строк OCR — и рамка обрезала облачко
                    // по буквам («[95,1191,303,1311]» вместо всего пузыря).
                    val area = if (r.box.area > 0f) r.box else Box(
                        left = r.lines.minOfOrNull { it.left } ?: 0f,
                        top = r.lines.minOfOrNull { it.top } ?: 0f,
                        right = r.lines.maxOfOrNull { it.right } ?: 0f,
                        bottom = r.lines.maxOfOrNull { it.bottom } ?: 0f
                    )
                    val left = (size.width * (area.left / refW) - padX).coerceAtLeast(0f)
                    val top = (size.height * (area.top / refH) - padY).coerceAtLeast(0f)
                    val w = size.width * (area.width / refW) + padX * 2
                    val h = size.height * (area.height / refH) + padY * 2
                    val tl = androidx.compose.ui.geometry.Offset(left, top)
                    val sz = androidx.compose.ui.geometry.Size(w, h)
                    // углы скругляем: облачко круглое, рамка-прямоугольник «резала глаз»
                    val radius = minOf(w, h) * 0.22f
                    val corner = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    val spoken = vm.isSpoken(index, r)
                    val isActive = livePage && r.index == active
                    when {
                        isActive -> {
                            drawRoundRect(Color(0x5A67E8F9), tl, sz, corner)
                            drawRoundRect(
                                Color(0x99000000), tl, sz, corner,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                            )
                            drawRoundRect(
                                Color(0xFF22D3EE), tl, sz, corner,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx())
                            )
                        }
                        // «молчание» (многоточие): облачко найдено, но читать нечего
                        r.silence -> {
                            drawRoundRect(Color(0x1F9CA3AF), tl, sz, corner)
                            drawRoundRect(
                                Color(0xFF9CA3AF), tl, sz, corner,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx())
                            )
                        }
                        // уже прозвучало — приглушённая зелёная рамка
                        spoken -> {
                            drawRoundRect(Color(0x1A34D399), tl, sz, corner)
                            drawRoundRect(
                                Color(0xFF34D399), tl, sz, corner,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx())
                            )
                        }
                        else -> {
                            drawRoundRect(Color(0x3322D3EE), tl, sz, corner)
                            drawRoundRect(
                                Color(0xFF0284C7), tl, sz, corner,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4.dp.toPx())
                            )
                        }
                    }
                    // тонкие рамки ровно по строкам текста: видно, что рамка облачка
                    // захватила пустоту, а строки — вот они
                    r.lines.forEach { ln ->
                        val ll = size.width * (ln.left / refW)
                        val lt = size.height * (ln.top / refH)
                        val lw = size.width * (ln.width / refW)
                        val lh = size.height * (ln.height / refH)
                        drawRect(
                            Color(0x55FFFFFF),
                            androidx.compose.ui.geometry.Offset(ll, lt),
                            androidx.compose.ui.geometry.Size(lw, lh),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                        )
                    }
                    // номер реплики — по нему легко найти облачко в панели «Текст страницы»
                    drawContext.canvas.nativeCanvas.drawText(
                        if (r.silence) "…" else "${r.index + 1}",
                        left + 4.dp.toPx(),
                        top + 13.dp.toPx(),
                        badgePaint
                    )
                }
            }
        }
    }
}

/** Непрерывная лента (вебтун и обычные главы). */
@Composable
private fun ContinuousReader(vm: AppViewModel) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val viewportPx = with(density) { cfg.screenHeightDp.dp.toPx() }
    val screenWidthPx = with(density) { cfg.screenWidthDp.dp.toPx() }
    // автопрокрутка: при озвучке лента сама едет к читаемой реплике, без рывков
    LaunchedEffect(vm.pageIndex, vm.activeReplica, vm.speaking) {
        if (!vm.speaking) return@LaunchedEffect
        val aspect = vm.pageAspect.coerceAtLeast(0.05f)
        val pageHeightPx = screenWidthPx / aspect
        val fraction = vm.activeReplicaFraction()
        val offset = if (fraction == null) 0
        else ((fraction * pageHeightPx) - viewportPx / 3f).toInt().coerceAtLeast(0)
        runCatching { state.animateScrollToItem(vm.pageIndex, offset) }
    }
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        items(vm.pageCount) { index ->
            PageView(vm, index, fillWidth = true)
        }
    }
}

/**
 * Панель «Текст страницы» в стиле КОМА:
 *  — секция «КАК ЧИТАЕТСЯ»: реплики по номерам, разбитые по полосам, с пометкой
 *    «○ распознано раньше», если текст взят из кеша этой книги;
 *  — секция «КАК РАСПОЗНАНО»: сырой текст движка, как есть;
 *  — кнопки «Распознать заново», «Прочитать», «Забыть текст этой книги», «Сбросить у всех книг».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageTextSheet(vm: AppViewModel) {
    val page = vm.pageText
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = { vm.showTextPanel = false },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Текст страницы ${vm.pageIndex + 1}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            val stats = page?.let {
                plural(it.rawLineCount, "строка", "строки", "строк") + " · " +
                    plural(it.replicas.size, "реплика", "реплики", "реплик") + " · " +
                    plural(it.bandCount, "полоса", "полосы", "полос") + " · " +
                    "рамок: ${it.replicas.count { r -> r.box.area > 0f }} · " +
                    "${it.elapsedMs} мс · ${it.engine}" +
                    if (it.latinLeft > 0) " · латиницы: ${it.latinLeft}" else " · без латиницы"
            } ?: "текст ещё не распознан"
            Text(
                stats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("поиск по тексту") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            if (page == null || page.replicas.isEmpty()) {
                Text(
                    if (vm.recognizing) "Распознавание…" else "Текст ещё не распознан — нажмите «Распознать заново»",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
            } else {
                val q = query.trim().lowercase()
                val shown = if (q.isEmpty()) page.replicas
                else page.replicas.filter { it.text.lowercase().contains(q) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SectionHeader("КАК ЧИТАЕТСЯ")
                    if (shown.isEmpty()) {
                        Text(
                            "Ничего не найдено по «${query.trim()}»",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    var lastBand = -1
                    shown.forEach { replica ->
                        if (replica.bandIndex != lastBand) {
                            lastBand = replica.bandIndex
                            BandDivider(
                                base = if (page.fromCache) "распознано раньше" else "распознано сейчас",
                                number = replica.bandIndex + 1,
                                cached = page.fromCache
                            )
                        }
                        ReplicaRow(vm, replica)
                    }
                }

                Spacer(Modifier.height(12.dp))
                SectionHeader("КАК РАСПОЗНАНО")
                Text(
                    page.rawText.ifBlank { "— движок не вернул сырой текст —" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 6.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.recognizeAgain(fullPage = true) },
                    modifier = Modifier.weight(1f)
                ) { Text("Распознать заново", style = MaterialTheme.typography.labelMedium) }
                Button(
                    onClick = {
                        vm.showTextPanel = false
                        vm.startReading()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Прочитать", style = MaterialTheme.typography.labelMedium) }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.forgetThisBook() },
                    modifier = Modifier.weight(1f)
                ) { Text("Забыть текст этой книги", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { vm.forgetAllBooks() },
                    modifier = Modifier.weight(1f)
                ) { Text("Сбросить у всех книг", style = MaterialTheme.typography.labelMedium) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.showTextPanel = false; vm.openJournal() },
                    modifier = Modifier.weight(1f)
                ) { Text("Журнал", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { vm.settings.perBookSettings = true; vm.saveBookSettings(); vm.statusMessage = "Настройки сохранены за книгой" },
                    modifier = Modifier.weight(1f)
                ) { Text("Запомнить настройки книги", style = MaterialTheme.typography.labelMedium) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.goToPagePrev() }) { Text("◀ стр. ${vm.pageIndex}") }
                OutlinedButton(onClick = { vm.goToPageNext() }) { Text("стр. ${vm.pageIndex + 2} ▶") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
    )
}

@Composable
private fun BandDivider(base: String, number: Int, cached: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "○ $base",
            style = MaterialTheme.typography.labelSmall,
            color = if (cached) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            "полоса $number",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}

@Composable
private fun ReplicaRow(vm: AppViewModel, replica: lv.bibliogid.manga.core.ocr.Replica) {
    val active = vm.activeReplica == replica.index
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable { vm.speakReplica(replica.index) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "${replica.index + 1}",
            modifier = Modifier.width(28.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
        Column(Modifier.weight(1f)) {
            Text(
                replica.text.ifBlank { "(текст не распознан)" },
                style = MaterialTheme.typography.bodyLarge
            )
            Row {
                Text(
                    typeLabel(replica.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (replica.needsReview) {
                    Text(
                        "  · проверить",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

private fun typeLabel(type: RegionType): String = when (type) {
    RegionType.BUBBLE -> "облачко"
    RegionType.THOUGHT -> "мысль"
    RegionType.CAPTION -> "плашка"
    RegionType.FREE_TEXT -> "текст поверх рисунка"
    RegionType.SFX -> "звук"
    RegionType.UNKNOWN -> "—"
}
