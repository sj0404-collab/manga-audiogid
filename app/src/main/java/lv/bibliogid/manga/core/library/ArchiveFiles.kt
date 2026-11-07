package lv.bibliogid.manga.core.library

enum class ArchiveKind { ZIP_CBZ, RAR_CBR, FOLDER, PDF, UNKNOWN }

/** Работа с содержимым архива/папки — только по именам, без распаковки. */
object ArchiveFiles {

    val imageExt = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "avif", "heic", "jxl")
    val archiveExt = setOf("cbz", "cbr", "zip", "rar", "cb7", "cbt")

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String): Boolean {
        val e = extOf(name)
        if (e !in imageExt) return false
        val base = name.substringAfterLast('/')
        if (base.startsWith(".")) return false
        if (name.contains("__MACOSX")) return false
        if (base.startsWith("._")) return false
        return true
    }

    fun isArchive(name: String): Boolean = extOf(name) in archiveExt

    fun kindOf(name: String): ArchiveKind = when (extOf(name)) {
        "cbz", "zip" -> ArchiveKind.ZIP_CBZ
        "cbr", "rar" -> ArchiveKind.RAR_CBR
        else -> if (isImage(name)) ArchiveKind.FOLDER else ArchiveKind.UNKNOWN
    }

    /** Страницы в правильном порядке (натуральная сортировка по имени внутри папки). */
    fun pageOrder(names: List<String>): List<String> {
        val base = names.firstOrNull { it.contains('/') }?.substringBeforeLast('/')?.length ?: 0
        return names.filter { isImage(it) }
            .sortedWith { a, b -> NaturalOrder.compare(nameKey(a, base), nameKey(b, base)) }
    }

    private fun nameKey(name: String, baseLen: Int): String =
        if (baseLen > 0 && name.length > baseLen) name.substring(baseLen).trimStart('/') else name

    private val coverHints = listOf("cover", "обложк", "front", "folder")

    /** Кандидат на обложку: явный cover/обложка, иначе первая страница по порядку. */
    fun chooseCover(names: List<String>): String? {
        val pages = pageOrder(names)
        if (pages.isEmpty()) return null
        val hinted = pages.firstOrNull { p ->
            val fn = p.substringAfterLast('/').lowercase()
            coverHints.any { h -> fn.contains(h) }
        }
        return hinted ?: pages.first()
    }
}
