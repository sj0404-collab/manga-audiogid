package lv.bibliogid.manga.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lv.bibliogid.manga.core.util.AppLog

/** Журнал работы: OCR, озвучка, перелистывание, ошибки. Можно отправить одним нажатием. */
@Composable
fun JournalScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val lines = AppLog.dump().lines().filter { it.isNotBlank() }.reversed()

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("←", fontSize = 22.sp, modifier = Modifier.clickable { vm.back() })
            Spacer(Modifier.width(10.dp))
            Text("Журнал", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val text = AppLog.dump()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "MangaBiblioGid — журнал")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching { context.startActivity(Intent.createChooser(intent, "Отправить журнал")) }
            }) { Text("Отправить") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Очистить") }
            // тот же журнал файлом .png: такой файл принимают приёмники, которые не берут .txt
            OutlinedButton(onClick = {
                val f = java.io.File(context.filesDir, "bibliogid-log.png")
                runCatching { f.writeText(AppLog.dump()) }
                shareArchive(context, f, "text/plain")
            }) { Text("Журнал .png") }
        }
        Spacer(Modifier.height(8.dp))
        val audio = vm.audioJournalFiles()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val picked = audio.take(8)
                if (picked.isEmpty()) return@OutlinedButton
                val uris = picked.map { f ->
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        context.packageName + ".files",
                        f
                    )
                }
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "audio/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(intent, "Отправить аудиожурнал")) }
            }) { Text("Аудио: ${audio.size}") }
            OutlinedButton(onClick = { vm.clearAudioJournal() }) { Text("Стереть аудио") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // аудиожурнал сразу архивом: вручную упаковывать WAV в zip не нужно
            Button(onClick = { shareArchive(context, vm.audioArchive(false), "application/zip") }) {
                Text("Аудио ZIP")
            }
            OutlinedButton(onClick = { shareArchive(context, vm.audioArchive(true), "image/png") }) {
                Text("Аудио .png")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "строк: ${lines.size} · ${AppLog.filePath()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines) { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Отдать готовый файл (журнал, архив аудио) во внешнее приложение. */
private fun shareArchive(context: android.content.Context, file: java.io.File?, mime: String) {
    if (file == null || !file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".files",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Отправить файл")) }
}
