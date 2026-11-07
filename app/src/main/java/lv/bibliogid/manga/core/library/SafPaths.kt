package lv.bibliogid.manga.core.library

/**
 * Преобразование SAF documentId в настоящий путь к файлу.
 *
 * Зачем: на Android 10+ внешнее хранилище отдаётся через FUSE, и открытие
 * архива по «/proc/self/fd/N» работает не всегда — ZipFile/RandomAccessFile
 * падают, и глава «не открывается». Надёжнее открыть настоящий путь.
 *
 * Форматы documentId:
 *   primary:Книги/Манга/Том 1.cbz       -> <primaryRoot>/Книги/Манга/Том 1.cbz
 *   1A2B-3C4D:Манга/Том 1.cbz           -> /storage/1A2B-3C4D/Манга/Том 1.cbz
 *   raw:/storage/emulated/0/x.cbz       -> /storage/emulated/0/x.cbz
 *   home:Манга/x.cbz                    -> <homeRoot>/Манга/x.cbz
 */
object SafPaths {

    fun fromDocumentId(
        documentId: String,
        primaryRoot: String = "/storage/emulated/0",
        homeRoot: String? = null
    ): String? {
        val id = documentId.trim()
        if (id.isEmpty()) return null
        if (id.startsWith("raw:")) return id.removePrefix("raw:").takeIf { it.isNotEmpty() }
        if (id.startsWith("msf:")) return null // MediaStore-файлы путём не открыть

        val volume = id.substringBefore(':', "")
        val rest = id.substringAfter(':', "")
        if (rest.isEmpty()) return null

        val root = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot
            volume.equals("home", ignoreCase = true) -> homeRoot ?: primaryRoot
            volume.isEmpty() -> primaryRoot
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "/storage/$volume"
            else -> "/storage/$volume"
        }
        return "${root.trimEnd('/')}/$rest"
    }

    /** Проверка, что путь похож на настоящий файл нужного типа. */
    fun looksLikeArchivePath(path: String?): Boolean =
        path != null && path.isNotBlank() && ArchiveFiles.isArchive(path)
}
