package lv.bibliogid.manga.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lv.bibliogid.manga.core.library.LibrarySort
import lv.bibliogid.manga.data.MangaItem

private val russianLetters = listOf('А', 'Б', 'В', 'Г', 'Д', 'Е', 'Ж', 'З', 'И', 'К', 'Л', 'М', 'Н',
    'О', 'П', 'Р', 'С', 'Т', 'У', 'Ф', 'Х', 'Ц', 'Ч', 'Ш', 'Э', 'Ю', 'Я')

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            vm.chooseTree(uri)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // верхние вкладки
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TabChip("Манга", selected = true, onClick = {})
            TabChip("Настройки", selected = false, onClick = { vm.screen = Screen.Settings })
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📚  Папок-манг: ${vm.library.size}  ·  Архивов: ${vm.library.sumOf { it.entry.chapterCount }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { picker.launch(null) }) { Text("+ папка") }
        }

        // поиск
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text("Поиск по папкам и архивам") },
            leadingIcon = { Text("🔍") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp)
        )

        // буквы
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TabChip("Все", selected = vm.letter == null, onClick = { vm.letter = null })
            russianLetters.forEach { letter ->
                TabChip(letter.toString(), selected = vm.letter == letter, onClick = {
                    vm.letter = if (vm.letter == letter) null else letter
                })
            }
        }

        // сортировка
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TabChip("А-Я", selected = vm.sort == LibrarySort.TITLE_ASC, onClick = { vm.sort = LibrarySort.TITLE_ASC })
            TabChip("Сначала новые", selected = vm.sort == LibrarySort.NEWEST_FIRST, onClick = { vm.sort = LibrarySort.NEWEST_FIRST })
            TabChip("По главам", selected = vm.sort == LibrarySort.CHAPTERS_DESC, onClick = { vm.sort = LibrarySort.CHAPTERS_DESC })
        }

        if (vm.scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        if (vm.library.isEmpty() && !vm.scanning) {
            EmptyLibrary(onPick = { picker.launch(null) }, hasFolder = vm.settings.libraryTreeUri != null, vm = vm)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(vm.filtered, key = { it.entry.id }) { item ->
                    MangaCard(vm, item) { vm.openManga(item.entry.id) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onPick: () -> Unit, hasFolder: Boolean, vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Библиотека пуста", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Выберите корневую папку: внутри неё — папки манги, в каждой — обложка и главы (CBZ/CBR или папки со страницами).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPick) { Text(if (hasFolder) "Выбрать другую папку" else "Выбрать папку") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.scan() }, enabled = hasFolder) { Text("Пересканировать") }
    }
}

@Composable
private fun MangaCard(vm: AppViewModel, item: MangaItem, onClick: () -> Unit) {
    var cover by remember(item.entry.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.entry.id) {
        cover = vm.repo.loadCover(item, 420)
    }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val bitmap = cover
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = item.entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("📖", fontSize = 32.sp)
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xAA000000))
                    .padding(6.dp)
            ) {
                Text(
                    "${item.entry.chapterCount} гл.",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.entry.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().clickable { onClick() }
        )
        Text(
            humanSize(item.entry.totalSizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format("%.2f ГБ", gb)
        mb >= 1 -> String.format("%.1f МБ", mb)
        else -> String.format("%.0f КБ", kb)
    }
}

@Composable
fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        onClick = onClick
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
