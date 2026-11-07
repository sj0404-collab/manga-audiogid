package lv.bibliogid.manga.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafPathsTest {

    @Test
    fun `primary volume разворачивается в путь внешнего хранилища`() {
        val p = SafPaths.fromDocumentId("primary:Книги/Манга/Том 1. Глава 14.cbz")
        assertEquals("/storage/emulated/0/Книги/Манга/Том 1. Глава 14.cbz", p)
    }

    @Test
    fun `sd-карта по uuid`() {
        val p = SafPaths.fromDocumentId("1A2B-3C4D:Manga/Meile_Том 1. Глава 14.cbz")
        assertEquals("/storage/1A2B-3C4D/Manga/Meile_Том 1. Глава 14.cbz", p)
    }

    @Test
    fun `raw путь возвращается как есть`() {
        assertEquals(
            "/storage/emulated/0/Manga/a.cbz",
            SafPaths.fromDocumentId("raw:/storage/emulated/0/Manga/a.cbz")
        )
    }

    @Test
    fun `mediastore не поддерживается`() {
        assertNull(SafPaths.fromDocumentId("msf:1234"))
    }

    @Test
    fun `пустой id не ломает`() {
        assertNull(SafPaths.fromDocumentId(""))
    }

    @Test
    fun `путь архива распознаётся`() {
        val p = SafPaths.fromDocumentId("primary:Manga/a.cbz")
        assertTrue(SafPaths.looksLikeArchivePath(p))
    }

    @Test
    fun `имя с подчёркиванием и пробелами сохраняется`() {
        val p = SafPaths.fromDocumentId("primary:Youth of Revelation/Meile_Том 1. Глава 14.cbz")
        assertEquals("/storage/emulated/0/Youth of Revelation/Meile_Том 1. Глава 14.cbz", p)
    }
}
