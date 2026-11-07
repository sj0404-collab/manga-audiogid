package lv.bibliogid.manga.data

import lv.bibliogid.manga.core.library.ArchiveFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Регрессия на «бесконечный спиннер»: страница в архиве должна читаться так,
 * чтобы каждый openEntry() возвращал НОВЫЙ поток (ZipFile-поток не перематывается).
 */
class ZipPageSourceTest {

    private lateinit var zipFile: File

    @Before
    fun setUp() {
        zipFile = File.createTempFile("test-manga", ".cbz")
        ZipOutputStream(zipFile.outputStream()).use { out ->
            listOf("10.jpg", "2.jpg", "1.jpg").forEach { name ->
                val payload = ByteArray(1024) { (it % 251).toByte() }
                out.putNextEntry(ZipEntry(name))
                out.write(payload)
                out.closeEntry()
            }
            out.putNextEntry(ZipEntry("Thumbs.db"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
        }
    }

    @After
    fun tearDown() {
        zipFile.delete()
    }

    private fun entryNames(): List<String> = ZipFile(zipFile).use { zip ->
        val out = ArrayList<String>()
        val e = zip.entries()
        while (e.hasMoreElements()) out.add(e.nextElement().name)
        out
    }

    @Test
    fun `страницы упорядочены натурально и мусор отброшен`() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), ArchiveFiles.pageOrder(entryNames()))
    }

    @Test
    fun `каждый openEntry возвращает свежий поток с полным содержимым`() {
        val source = ZipPageSource(ZipFile(zipFile), "test.cbz", ArchiveFiles.pageOrder(entryNames()))
        try {
            assertEquals(3, source.pageCount)
            val first = source.openEntry(0)
            assertNotNull(first)
            val bytes1 = first!!.readBytes()
            assertEquals(1024, bytes1.size)

            // повторное открытие — снова полный файл (именно это ломалось)
            val bytes2 = source.openEntry(0)!!.readBytes()
            assertEquals(1024, bytes2.size)
            assertTrue(bytes1.contentEquals(bytes2))

            assertEquals(1024, source.openEntry(2)!!.readBytes().size)
        } finally {
            source.close()
        }
    }

    @Test
    fun `выход за границы не падает`() {
        val source = ZipPageSource(ZipFile(zipFile), "test.cbz", ArchiveFiles.pageOrder(entryNames()))
        try {
            assertEquals(null, source.openEntry(99))
            assertEquals(null, source.openEntry(-1))
        } finally {
            source.close()
        }
    }

    @Test
    fun `размер декодирования подбирается по ширине экрана`() {
        val source = ZipPageSource(ZipFile(zipFile), "test.cbz", ArchiveFiles.pageOrder(entryNames()))
        try {
            assertEquals(2, PageSource.sampleSize(3000, 4500, 1080))
        } finally {
            source.close()
        }
    }
}
