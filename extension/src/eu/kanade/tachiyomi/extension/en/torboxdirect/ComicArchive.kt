package eu.kanade.tachiyomi.extension.en.torboxdirect

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class ComicPage(val name: String, val type: String, val bytes: ByteArray)

internal object ComicArchive {
    private const val MAX_ARCHIVE = 32 * 1024 * 1024
    private const val MAX_EXPANDED = 48 * 1024 * 1024
    private const val MAX_PAGE = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 600
    private val types = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "webp" to "image/webp", "gif" to "image/gif",
    )
    private val digits = Regex("[0-9]+")

    class InvalidArchive(message: String) : IOException(message)

    fun read(input: InputStream, contentLength: Long): List<ComicPage> {
        if (contentLength > MAX_ARCHIVE) throw InvalidArchive("Use a CBZ under 32 MiB for this test.")
        val pages = mutableListOf<ComicPage>()
        var total = 0
        var count = 0
        val buffer = ByteArray(8192)
        ZipInputStream(LimitedInput(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw InvalidArchive("Too many archive entries.")
                val type = types[entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)]
                    ?.takeUnless { entry.isDirectory || entry.name.split('/').any { part ->
                        part == "__MACOSX" || part.startsWith(".")
                    } }
                val page = if (type == null) null else ByteArrayOutputStream()
                while (true) {
                    val read = zip.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_EXPANDED) throw InvalidArchive("Expanded archive is too large.")
                    if (page != null && page.size() + read > MAX_PAGE) {
                        throw InvalidArchive("An image exceeds the 8 MiB page limit.")
                    }
                    page?.write(buffer, 0, read)
                }
                if (type != null && page != null && page.size() > 0) {
                    pages += ComicPage(entry.name, type, page.toByteArray())
                }
            }
        }
        if (pages.isEmpty()) throw InvalidArchive("No images found. Try a CBZ with JPEG or PNG pages.")
        return pages.sortedBy { page -> digits.replace(page.name.lowercase(Locale.ROOT)) {
            it.value.padStart(12, '0')
        } }
    }

    private class LimitedInput(input: InputStream) : FilterInputStream(input) {
        private var remaining = MAX_ARCHIVE

        override fun read(): Int = super.read().also {
            if (it != -1 && --remaining < 0) throw InvalidArchive("Archive exceeds 32 MiB.")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, remaining + 1)).also {
                if (it > 0) remaining -= it
                if (remaining < 0) throw InvalidArchive("Archive exceeds 32 MiB.")
            }
    }
}
