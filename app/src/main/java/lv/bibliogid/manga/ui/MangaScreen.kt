package lv.bibliogid.manga.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import lv.bibliogid.manga.core.library.ReadingModeHint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MangaScreen(vm: AppViewModel) {
    val manga = vm.currentManga ?: run {
        LaunchedEffect(Unit) { vm.screen = Screen.Library }
        return
    }
    var cover by remember(manga.entry.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(manga.entry.id) { cover = vm.repo.loadCover(manga, 700) }

    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale("ru")) }
    val modeLabel = when (manga.entry.readingMode) {
        ReadingModeHint.MANGA_RTL -> "манга · справа налево"
        ReadingModeHint.COMIC_LTR -> "комикс · слева направо"
        ReadingModeHint.WEBTOON_VERTICAL -> "вебтун · вертикально"
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .width(130.dp)
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val bmp = cover
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = manga.entry.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("📖", fontSize = 30.sp) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(manga.entry.title, style = MaterialTheme.typography.titleLarge, maxLines = 3)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Глав: ${manga.entry.chapterCount} · ${humanSize(manga.entry.totalSizeBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        modeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { vm.openChapter(0) }) { Text("Читать") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { vm.screen = Screen.Settings }) { Text("Движок: ${vm.engineStatusText()}") }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", Modifier.clickable { vm.back() }, fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Text("Главы", style = MaterialTheme.typography.titleMedium)
            }
        }
        itemsIndexed(manga.entry.chapters) { index, chapter ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.openChapter(index) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        chapter.info.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2
                    )
                    Row {
                        Text(
                            if (chapter.lastModified > 0) dateFormat.format(Date(chapter.lastModified)) else "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "  ·  " + if (chapter.isArchive) "архив" else "папка" +
                                if (chapter.pageCount > 0) " · ${chapter.pageCount} стр." else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (chapter.sizeBytes > 0) {
                            Text(
                                "  ·  " + humanSize(chapter.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text("▶", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
