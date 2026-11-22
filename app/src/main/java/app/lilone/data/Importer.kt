package app.lilone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class ImportResult(
    val imported: List<String>,
    /** Files we could not read at all. */
    val failed: List<String>,
    /** Office files MuPDF couldn't convert: offer to open them in another app instead. */
    val needsConversion: List<Uri>,
    /** Files that were already in the library: ids of the existing copies (nothing new was added). */
    val alreadyHad: List<String> = emptyList(),
)

object Importer {
    private const val A4_WIDTH = 595f
    private const val A4_HEIGHT = 842f

    private data class Item(val uri: Uri, val name: String, val mime: String) {
        val ext get() = name.substringAfterLast('.', "").lowercase()
        val isPdf get() = mime == "application/pdf" || ext == "pdf"
        val isImage get() = mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
    }

    suspend fun import(context: Context, uris: List<Uri>, subjectId: String?): ImportResult = withContext(Dispatchers.IO) {
        val repo = LibraryRepo.get(context)
        val items = uris.map { describe(context, it) }
        val imported = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val convert = mutableListOf<Uri>()
        val alreadyHad = mutableListOf<String>()
        val work = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }

        // Photos / scans picked together become one PDF, one page per photo.
        val images = items.filter { it.isImage && !it.isPdf }
        if (images.isNotEmpty()) {
            val id = repo.newId()
            val out = repo.file(id)
            val ok = runCatching { imagesToPdf(context, images, work, out) }.getOrDefault(false)
            if (ok) {
                val title = if (images.size == 1) images[0].name.substringBeforeLast('.')
                else "Scan " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
                finishImport(repo, id, title, subjectId)?.let { imported += it } ?: failed.add(title)
            } else {
                out.delete(); failed += images.map { it.name }
            }
        }

        for (item in items.filterNot { it in images }) {
            val id = repo.newId()
            val out = repo.file(id)
            val title = item.name.substringBeforeLast('.').ifBlank { "Document" }
            if (item.isPdf) {
                val hash = runCatching {
                    context.contentResolver.openInputStream(item.uri)!!.use { copyHashing(it, out) }
                }.getOrNull()
                val existing = hash?.let { findExisting(repo, it, title) }
                when {
                    hash == null -> { out.delete(); failed += item.name }
                    existing != null -> { out.delete(); alreadyHad += existing }
                    else -> finishImport(repo, id, title, subjectId, hash)?.let { imported += it } ?: run { out.delete(); failed += item.name }
                }
            } else {
                // Word, PowerPoint, Excel, EPUB, XPS... MuPDF lays them out, we write them as a PDF.
                val src = File(work, "src_${System.nanoTime()}.${item.ext.ifEmpty { extFromMime(item.mime) }}")
                var hash: String? = null
                var existing: String? = null
                val ok = runCatching {
                    hash = context.contentResolver.openInputStream(item.uri)!!.use { copyHashing(it, src) }
                    existing = findExisting(repo, hash!!, title)
                    existing != null || documentToPdf(src, out)
                }.onFailure { android.util.Log.w("LilOne", "Could not convert ${item.name}", it) }.getOrDefault(false)
                when {
                    existing != null -> { out.delete(); alreadyHad += existing!! }
                    ok -> finishImport(repo, id, title, subjectId, hash)?.let { imported += it } ?: run { out.delete(); convert += item.uri }
                    else -> { out.delete(); convert += item.uri }
                }
            }
        }
        work.deleteRecursively()
        ImportResult(imported, failed, convert, alreadyHad)
    }

    /** Copies [input] to [out] and returns the SHA-256 of what was copied. */
    private fun copyHashing(input: java.io.InputStream, out: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input, md).use { din -> FileOutputStream(out).use { din.copyTo(it, 1 shl 16) } }
        return md.digest().toHex()
    }

    private fun hashFile(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().toHex()
    }.getOrNull()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /**
     * The existing copy of this exact file, if there is one. Files added before v1.3 have no fingerprint,
     * so for those we check same-named files whose PDF is still byte-identical (i.e. not annotated yet).
     */
    private fun findExisting(repo: LibraryRepo, hash: String, title: String): String? {
        val docs = repo.state.value.docs
        docs.firstOrNull { it.sourceHash == hash }?.let { return it.id }
        val legacy = docs.filter { it.sourceHash == null && it.title.equals(title, ignoreCase = true) }
            .firstOrNull { hashFile(repo.file(it.id)) == hash } ?: return null
        repo.updateDoc(legacy.id) { it.copy(sourceHash = hash) }
        return legacy.id
    }

    /** Validates the PDF, makes a thumbnail and adds it to the library. Returns the id, or null if unreadable. */
    private fun finishImport(repo: LibraryRepo, id: String, title: String, subjectId: String?, sourceHash: String? = null): String? {
        val file = repo.file(id)
        var pages = 0
        try {
            val doc = Document.openDocument(file.path)
            try {
                if (!doc.needsPassword()) {
                    pages = doc.countPages()
                    if (pages <= 0) return null
                    makeThumb(doc, repo.thumb(id))
                }
            } finally {
                doc.destroy()
            }
        } catch (e: Throwable) {
            return null
        }
        repo.addDoc(Doc(id = id, title = title, subjectId = subjectId, added = System.currentTimeMillis(), pageCount = pages, sourceHash = sourceHash))
        return id
    }

    fun makeThumb(doc: Document, out: File) {
        runCatching {
            val page = doc.loadPage(0)
            val bmp = AndroidDrawDevice.drawPageFitWidth(page, 420)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            page.destroy()
        }
    }

    fun refreshThumb(pdf: File, out: File) {
        runCatching {
            val doc = Document.openDocument(pdf.path)
            if (!doc.needsPassword()) makeThumb(doc, out)
            doc.destroy()
        }
    }

    private fun imagesToPdf(context: Context, images: List<Item>, work: File, out: File): Boolean {
        val writer = DocumentWriter(out.path, "pdf", "compress")
        var pages = 0
        try {
            images.forEachIndexed { n, item ->
                val jpg = File(work, "img_$n.jpg")
                val bmp = decodeImage(context, item.uri) ?: return@forEachIndexed
                FileOutputStream(jpg).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                val doc = Document.openDocument(jpg.path)
                val page = doc.loadPage(0)
                val b = page.bounds
                val s = A4_WIDTH / max(1f, b.x1 - b.x0)
                val dev = writer.beginPage(Rect(0f, 0f, A4_WIDTH, (b.y1 - b.y0) * s))
                page.run(dev, Matrix(s))
                writer.endPage()
                page.destroy(); doc.destroy()
                pages++
            }
        } finally {
            writer.close()
        }
        return pages > 0
    }

    private fun decodeImage(context: Context, uri: Uri, maxSide: Int = 2400): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = maxSide.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    private fun documentToPdf(src: File, out: File): Boolean {
        val doc = Document.openDocument(src.path)
        try {
            if (doc.needsPassword()) return false
            if (doc.isReflowable) doc.layout(A4_WIDTH, A4_HEIGHT, 11f)
            val count = doc.countPages()
            if (count <= 0) return false
            val writer = DocumentWriter(out.path, "pdf", "compress")
            try {
                for (i in 0 until count) {
                    val page = doc.loadPage(i)
                    val b = page.bounds
                    val dev = writer.beginPage(b)
                    page.run(dev, Matrix())
                    writer.endPage()
                    page.destroy()
                }
            } finally {
                writer.close()
            }
            return true
        } finally {
            doc.destroy()
        }
    }

    private fun describe(context: Context, uri: Uri): Item {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension((name ?: uri.lastPathSegment ?: "").substringAfterLast('.', ""))
            ?: ""
        val n = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        return Item(uri, n, mime)
    }

    private fun extFromMime(mime: String) = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
}
