package lv.bibliogid.manga.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lv.bibliogid.manga.core.library.ReadingModeHint

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.chooseTree(uri)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.importModels(uri)
    }
    var yandexKey by remember { mutableStateOf(vm.settings.apiKeyYandex) }
    var yandexFolder by remember { mutableStateOf(vm.settings.folderIdYandex) }
    var googleKey by remember { mutableStateOf(vm.settings.apiKeyGoogle) }
    var ocrSpaceKey by remember { mutableStateOf(vm.settings.apiKeyOcrSpace) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("←", fontSize = 22.sp, modifier = Modifier.clickable { vm.screen = Screen.Library })
                Spacer(Modifier.width(12.dp))
                Text("Настройки", style = MaterialTheme.typography.titleLarge)
            }
        }

        item { SectionTitle("Библиотека") }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Корневая папка", style = MaterialTheme.typography.titleMedium)
                    Text(
                        vm.settings.libraryTreeUri ?: "не выбрана",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(null) }) { Text("Выбрать") }
                        OutlinedButton(onClick = { vm.scan() }) { Text("Пересканировать") }
                    }
                }
            }
        }

        item { SectionTitle("Распознавание текста") }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Онлайн-движок", modifier = Modifier.weight(1f))
                        Switch(checked = vm.settings.useOnline, onCheckedChange = { vm.settings.useOnline = it })
                    }
                    Text(
                        "Выключено — всё на устройстве (без сети). Включено — облако (Yandex/Google/OCR.space). Tesseract не используется ни в одном режиме.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Локальный движок", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabChip("PaddleOCR (кириллица)", vm.settings.localEngine == "paddle") {
                            vm.settings.localEngine = "paddle"
                        }
                        TabChip("ML Kit", vm.settings.localEngine == "mlkit") {
                            vm.settings.localEngine = "mlkit"
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (vm.models.isReady) "Модели: ${humanSize(vm.models.sizeBytes())} — готовы"
                        else "Модели не скачаны (~13 МБ, один раз, дальше офлайн)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadModels() }, enabled = !vm.modelsBusy) {
                            Text(if (vm.modelsBusy) vm.modelsProgress.ifBlank { "Загрузка…" } else "Скачать модели OCR")
                        }
                        OutlinedButton(onClick = { vm.deleteModels() }) { Text("Удалить") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { modelPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Импорт моделей из папки (det.onnx, rec.onnx, dict.txt)") }
                    vm.models.lastError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Последняя ошибка: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item { SectionTitle("Облачные движки (свои ключи)") }
        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabChip("Yandex", vm.settings.onlineEngine == "yandex") { vm.settings.onlineEngine = "yandex" }
                        TabChip("Google", vm.settings.onlineEngine == "google") { vm.settings.onlineEngine = "google" }
                        TabChip("OCR.space", vm.settings.onlineEngine == "ocrspace") { vm.settings.onlineEngine = "ocrspace" }
                    }
                    OutlinedTextField(
                        value = yandexKey, onValueChange = { yandexKey = it; vm.settings.apiKeyYandex = it },
                        label = { Text("Yandex Api-Key") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = yandexFolder, onValueChange = { yandexFolder = it; vm.settings.folderIdYandex = it },
                        label = { Text("Yandex folderId") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = googleKey, onValueChange = { googleKey = it; vm.settings.apiKeyGoogle = it },
                        label = { Text("Google Vision API key") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ocrSpaceKey, onValueChange = { ocrSpaceKey = it; vm.settings.apiKeyOcrSpace = it },
                        label = { Text("OCR.space API key") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item { SectionTitle("Чтение и озвучка") }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Скорость TTS: ${String.format("%.2f", vm.settings.ttsSpeed)}x")
                    Slider(
                        value = vm.settings.ttsSpeed,
                        onValueChange = { vm.settings.ttsSpeed = it },
                        valueRange = 0.25f..2.0f
                    )
                    Text("Пауза на странице без текста: " +
                        String.format("%.1f", vm.settings.emptyPageDwellSec) + " с")
                    Slider(
                        value = vm.settings.emptyPageDwellSec,
                        onValueChange = { vm.settings.emptyPageDwellSec = it },
                        valueRange = 1f..10f
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Скрывать меню при чтении")
                            Text(
                                "панели убираются на время озвучки, тап возвращает",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vm.settings.hideUiWhileReading,
                            onCheckedChange = { vm.settings.hideUiWhileReading = it }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Свои настройки для каждой книги")
                            Text(
                                "скорость, автоозвучка, рамки, лента и паузы сохраняются за книгой",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vm.settings.perBookSettings,
                            onCheckedChange = {
                                vm.settings.perBookSettings = it
                                if (it) vm.saveBookSettings()
                            }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Журнал работы", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.openJournal() }) { Text("Открыть") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Автоперелистывание", modifier = Modifier.weight(1f))
                        Switch(
                            checked = vm.settings.ttsAutoPageTurn,
                            onCheckedChange = { vm.settings.ttsAutoPageTurn = it }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Непрерывная лента", modifier = Modifier.weight(1f))
                        Switch(
                            checked = vm.continuousMode,
                            onCheckedChange = { vm.setContinuous(it) }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Рамки найденного текста")
                            Text(
                                "показывать на странице, что распознано",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vm.framesEnabled,
                            onCheckedChange = { vm.setFrames(it) }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Распознавать страницу сразу")
                            Text(
                                "при открытии страницы запускать OCR",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vm.settings.autoOcr,
                            onCheckedChange = { vm.settings.autoOcr = it }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Порядок чтения", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabChip("Манга →←", vm.settings.readMode == "MANGA_RTL") { vm.settings.readMode = "MANGA_RTL" }
                        TabChip("Комикс →", vm.settings.readMode == "COMIC_LTR") { vm.settings.readMode = "COMIC_LTR" }
                        TabChip("Вебтун ⇅", vm.settings.readMode == "WEBTOON_VERTICAL") {
                            vm.settings.readMode = "WEBTOON_VERTICAL"
                        }
                    }
                }
            }
        }

        item { SectionTitle("Производительность") }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Кеш страниц в памяти: ${vm.settings.pageCacheSize} (0 = без кеша)")
                    Slider(
                        value = vm.settings.pageCacheSize.toFloat(),
                        onValueChange = { vm.settings.pageCacheSize = it.toInt() },
                        valueRange = 0f..12f,
                        steps = 11
                    )
                    Text(
                        "Страницы не кешируются на диск: архив читается напрямую, страница декодируется по требованию.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.textStore.clearAll() }) { Text("Очистить кеш текста") }
                }
            }
        }

        item {
            Text(
                "Локальный OCR: PaddleOCR (ONNX, кириллица) или ML Kit — оба на устройстве. " +
                    "Облачные движки подключаются по вашему ключу. Tesseract не используется.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}
