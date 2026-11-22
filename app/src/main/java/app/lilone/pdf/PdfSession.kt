package app.lilone.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Page box in PDF points. */
data class PageBox(val x0: Float, val y0: Float, val w: Float, val h: Float)

data class OutlineItem(val title: String, val level: Int, val page: Int)

data class SearchHit(val page: Int, val quads: List<Quad>, val snippet: String)

data class History(val canUndo: Boolean, val canRedo: Boolean)

enum class AnnotKind { Highlight, Underline, Strike, Ink, Text, Note, Box, Arrow, Other }

data class AnnotInfo(
    val page: Int,
    val index: Int,
    val kind: AnnotKind,
    val color: Int,
    val contents: String,
    val bounds: RectF,
)

/**
 * Wraps one open MuPDF document. MuPDF objects are not thread-safe, so every call runs on a
 * single dedicated thread ([dispatcher]).
 */
class PdfSession private constructor(
    val path: String,
    private val doc: Document,
    private val dispatcher: ExecutorCoroutineDispatcher,
) {
    private val pdf: PDFDocument? = (doc as? PDFDocument) ?: doc.asPDF()
    var boxes: List<PageBox> = emptyList(); private set
    val pageCount get() = boxes.size
    val needsPassword get() = doc.needsPassword()
    /** True when there are edits not yet written to [path]. */
    private var dirty = false

    private val pages = object : LinkedHashMap<Int, Page>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Page>): Boolean {
            val drop = size > 12
            if (drop) runCatching { e.value.destroy() }
            return drop
        }
    }
    private val texts = object : LinkedHashMap<Int, StructuredText>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, StructuredText>): Boolean {
            val drop = size > 4
            if (drop) runCatching { e.value.destroy() }
            return drop
        }
    }

    private suspend fun <T> mu(block: () -> T): T = withContext(dispatcher) { block() }

    private fun page(i: Int): Page = pages.getOrPut(i) { doc.loadPage(i) }
    private fun pdfPage(i: Int): PDFPage? = page(i) as? PDFPage
    private fun stext(i: Int): StructuredText = texts.getOrPut(i) { page(i).toStructuredText("preserve-whitespace") }

    private fun dropCaches() {
        pages.values.forEach { runCatching { it.destroy() } }; pages.clear()
        texts.values.forEach { runCatching { it.destroy() } }; texts.clear()
    }

    suspend fun authenticate(password: String): Boolean = mu {
        val ok = doc.authenticatePassword(password)
        if (ok) prepare()
        ok
    }

    private fun prepare() {
        boxes = (0 until doc.countPages()).map { i ->
            val b = page(i).bounds
            PageBox(b.x0, b.y0, max(1f, b.x1 - b.x0), max(1f, b.y1 - b.y0))
        }
        runCatching { pdf?.enableJournal() }
    }

    // ------------------------------------------------------------------ rendering

    suspend fun render(i: Int, widthPx: Int): Bitmap? = mu {
        try {
            val box = boxes[i]
            val scale = widthPx / box.w
            AndroidDrawDevice.drawPage(page(i), Matrix(scale))
        } catch (e: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ text (for the study assistant)

    suspend fun pageText(i: Int): String = mu { runCatching { stext(i).asText() }.getOrDefault("").trim() }

    suspend fun regionText(i: Int, r: RectF): String = mu {
        runCatching { stext(i).copy(Point(r.left, r.top), Point(r.right, r.bottom)) }.getOrDefault("").trim()
    }

    /** Renders just [r] (page coordinates) to a bitmap, at most [maxPx] on the long side. */
    suspend fun renderRegion(i: Int, r: RectF, maxPx: Int = 1400): Bitmap? = mu {
        runCatching {
            val s = (maxPx / max(r.width(), r.height())).coerceIn(1f, 4f)
            val w = (r.width() * s).toInt().coerceAtLeast(1)
            val h = (r.height() * s).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            val dev = AndroidDrawDevice(bmp, (r.left * s).toInt(), (r.top * s).toInt())
            try {
                page(i).run(dev, Matrix(s), null)
                dev.close()
            } finally {
                dev.destroy()
            }
            bmp
        }.getOrNull()
    }

    // ------------------------------------------------------------------ text

    /** Expands the selection so it starts and ends on whole words. */
    private fun snapped(i: Int, a: PointF, b: PointF): Pair<Point, Point> {
        val pa = Point(a.x, a.y)
        val pb = Point(b.x, b.y)
        runCatching { stext(i).snapSelection(pa, pb, StructuredText.SELECT_WORDS) }
        return pa to pb
    }

    suspend fun selectionQuads(i: Int, a: PointF, b: PointF): List<Quad> = mu {
        runCatching {
            val (pa, pb) = snapped(i, a, b)
            stext(i).highlight(pa, pb).toList()
        }.getOrDefault(emptyList())
    }

    suspend fun selectionText(i: Int, a: PointF, b: PointF): String = mu {
        runCatching {
            val (pa, pb) = snapped(i, a, b)
            stext(i).copy(pa, pb)
        }.getOrDefault("").replace(Regex("\\s+"), " ").trim()
    }

    // ------------------------------------------------------------------ annotations

    private inline fun <T> op(name: String, block: () -> T): T {
        val p = pdf ?: return block()
        p.beginOperation(name)
        try {
            val r = block()
            p.endOperation()
            dirty = true
            return r
        } catch (e: Throwable) {
            runCatching { p.abandonOperation() }
            throw e
        }
    }

    private fun finish(i: Int, a: PDFAnnotation) {
        a.update()
        pdfPage(i)?.update()
    }

    suspend fun addMarkup(i: Int, kind: AnnotKind, quads: List<Quad>, color: Int, text: String) = mu {
        val p = pdfPage(i) ?: return@mu
        op(kind.name) {
            val type = when (kind) {
                AnnotKind.Underline -> PDFAnnotation.TYPE_UNDERLINE
                AnnotKind.Strike -> PDFAnnotation.TYPE_STRIKE_OUT
                else -> PDFAnnotation.TYPE_HIGHLIGHT
            }
            val a = p.createAnnotation(type)
            a.setColor(rgb(color))
            a.setQuadPoints(quads.toTypedArray())
            if (text.isNotBlank()) a.setContents(text)
            finish(i, a)
        }
    }

    suspend fun addInk(i: Int, strokes: List<List<PointF>>, color: Int, width: Float, opacity: Float) = mu {
        val p = pdfPage(i) ?: return@mu
        op("Draw") {
            val a = p.createAnnotation(PDFAnnotation.TYPE_INK)
            a.setColor(rgb(color))
            a.setBorderWidth(width)
            if (opacity < 1f) a.setOpacity(opacity)
            a.setInkList(strokes.map { s -> s.map { Point(it.x, it.y) }.toTypedArray() }.toTypedArray())
            finish(i, a)
        }
    }

    /** Adds typed text whose top-left corner is at (x, y). The box grows to fit the text. */
    suspend fun addFreeText(i: Int, x: Float, y: Float, text: String, size: Float, color: Int) = mu {
        val p = pdfPage(i) ?: return@mu
        val box = boxes[i]
        op("Text") {
            val a = p.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
            a.setRect(freeTextRect(box, x, y, text, size))
            a.setContents(text)
            a.setDefaultAppearance("Helv", size, rgb(color))
            a.setBorderWidth(0f)
            finish(i, a)
        }
    }

    private fun freeTextRect(box: PageBox, x: Float, y: Float, text: String, size: Float): Rect {
        val paint = TextPaint().apply { textSize = size; isAntiAlias = true }
        val maxW = max(60f, box.x0 + box.w - x - 8f)
        val natural = text.lines().maxOf { paint.measureText(it) } + size * 0.8f
        val w = min(maxW, max(size * 3, natural))
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val h = layout.lineCount * size * 1.25f + size * 0.6f
        return Rect(x, y, x + w + size * 0.4f, y + h)
    }

    suspend fun addNote(i: Int, x: Float, y: Float, text: String, color: Int) = mu {
        val p = pdfPage(i) ?: return@mu
        op("Note") {
            val a = p.createAnnotation(PDFAnnotation.TYPE_TEXT)
            a.setRect(Rect(x - 10f, y - 10f, x + 10f, y + 10f))
            a.setIcon("Comment")
            a.setColor(rgb(color))
            a.setContents(text)
            finish(i, a)
        }
    }

    suspend fun addBox(i: Int, r: RectF, color: Int, width: Float) = mu {
        val p = pdfPage(i) ?: return@mu
        op("Box") {
            val a = p.createAnnotation(PDFAnnotation.TYPE_SQUARE)
            a.setRect(Rect(r.left, r.top, r.right, r.bottom))
            a.setColor(rgb(color))
            a.setBorderWidth(width)
            finish(i, a)
        }
    }

    suspend fun addArrow(i: Int, from: PointF, to: PointF, color: Int, width: Float) = mu {
        val p = pdfPage(i) ?: return@mu
        op("Arrow") {
            val a = p.createAnnotation(PDFAnnotation.TYPE_LINE)
            a.setLine(Point(from.x, from.y), Point(to.x, to.y))
            a.setLineEndingStyles(PDFAnnotation.LINE_ENDING_NONE, PDFAnnotation.LINE_ENDING_OPEN_ARROW)
            a.setColor(rgb(color))
            a.setBorderWidth(width)
            finish(i, a)
        }
    }

    private fun kindOf(type: Int) = when (type) {
        PDFAnnotation.TYPE_HIGHLIGHT -> AnnotKind.Highlight
        PDFAnnotation.TYPE_UNDERLINE, PDFAnnotation.TYPE_SQUIGGLY -> AnnotKind.Underline
        PDFAnnotation.TYPE_STRIKE_OUT -> AnnotKind.Strike
        PDFAnnotation.TYPE_INK -> AnnotKind.Ink
        PDFAnnotation.TYPE_FREE_TEXT -> AnnotKind.Text
        PDFAnnotation.TYPE_TEXT -> AnnotKind.Note
        PDFAnnotation.TYPE_SQUARE, PDFAnnotation.TYPE_CIRCLE -> AnnotKind.Box
        PDFAnnotation.TYPE_LINE -> AnnotKind.Arrow
        else -> AnnotKind.Other
    }

    private val ignoredTypes = setOf(PDFAnnotation.TYPE_LINK, PDFAnnotation.TYPE_POPUP, PDFAnnotation.TYPE_WIDGET)

    private fun hits(a: PDFAnnotation, x: Float, y: Float, r: Float): Boolean = when (a.type) {
        PDFAnnotation.TYPE_INK -> {
            val reach = r + a.borderWidth / 2f
            a.inkList.any { s -> nearPolyline(s, x, y, reach) }
        }
        PDFAnnotation.TYPE_HIGHLIGHT, PDFAnnotation.TYPE_UNDERLINE,
        PDFAnnotation.TYPE_STRIKE_OUT, PDFAnnotation.TYPE_SQUIGGLY ->
            a.quadPoints.any { q -> q.toRect().let { x >= it.x0 - r && x <= it.x1 + r && y >= it.y0 - r && y <= it.y1 + r } }
        else -> a.bounds.let { x >= it.x0 - r && x <= it.x1 + r && y >= it.y0 - r && y <= it.y1 + r }
    }

    private fun findAt(i: Int, x: Float, y: Float, r: Float): Pair<Int, PDFAnnotation>? {
        val list = pdfPage(i)?.annotations ?: return null
        for (idx in list.indices.reversed()) {
            val a = list[idx]
            if (a.type in ignoredTypes) continue
            if (hits(a, x, y, r)) return idx to a
        }
        return null
    }

    /** Deletes the top-most annotation under the point. Returns true if something was erased. */
    suspend fun eraseAt(i: Int, x: Float, y: Float, r: Float): Boolean = mu {
        val p = pdfPage(i) ?: return@mu false
        val (_, a) = findAt(i, x, y, r) ?: return@mu false
        op("Erase") { p.deleteAnnotation(a); p.update() }
        true
    }

    suspend fun hitTest(i: Int, x: Float, y: Float, r: Float): AnnotInfo? = mu {
        val (idx, a) = findAt(i, x, y, r) ?: return@mu null
        info(i, idx, a)
    }

    private fun info(i: Int, idx: Int, a: PDFAnnotation): AnnotInfo {
        val b = a.bounds
        return AnnotInfo(i, idx, kindOf(a.type), argb(a.color), a.contents ?: "", RectF(b.x0, b.y0, b.x1, b.y1))
    }

    suspend fun updateAnnot(i: Int, index: Int, contents: String?, color: Int?) = mu {
        val p = pdfPage(i) ?: return@mu
        val a = p.annotations?.getOrNull(index) ?: return@mu
        op("Edit") {
            if (color != null) a.setColor(rgb(color))
            if (contents != null) {
                a.setContents(contents)
                if (a.type == PDFAnnotation.TYPE_FREE_TEXT) {
                    val da = a.defaultAppearance
                    val size = da?.size?.takeIf { it > 0 } ?: 14f
                    val r = a.rect
                    a.setRect(freeTextRect(boxes[i], r.x0, r.y0, contents, size))
                }
            }
            finish(i, a)
        }
    }

    suspend fun deleteAnnot(i: Int, index: Int) = mu {
        val p = pdfPage(i) ?: return@mu
        val a = p.annotations?.getOrNull(index) ?: return@mu
        op("Delete") { p.deleteAnnotation(a); p.update() }
    }

    suspend fun history(): History = mu {
        val p = pdf ?: return@mu History(false, false)
        History(runCatching { p.canUndo() }.getOrDefault(false), runCatching { p.canRedo() }.getOrDefault(false))
    }

    suspend fun undo() = mu { pdf?.let { if (it.canUndo()) { it.undo(); dropCaches(); dirty = true } } }
    suspend fun redo() = mu { pdf?.let { if (it.canRedo()) { it.redo(); dropCaches(); dirty = true } } }

    // ------------------------------------------------------------------ saving

    suspend fun hasChanges(): Boolean = mu { dirty }

    /**
     * Writes the whole document (with annotations) to a temp file and atomically swaps it in.
     *
     * We deliberately don't use MuPDF's "incremental" save to the open file: it only works once per
     * opened document (a second incremental save rewrites the file from the original bytes and drops
     * the first batch of changes). The open document keeps reading from the old file handle, so
     * replacing the file underneath it is safe, and undo history is kept.
     */
    suspend fun save(): Boolean = mu {
        val p = pdf ?: return@mu false
        if (!dirty) return@mu true
        val tmp = File("$path.saving")
        try {
            p.save(tmp.path, "")
            if (!tmp.renameTo(File(path))) error("rename failed")
            dirty = false
            true
        } catch (e: Throwable) {
            tmp.delete()
            false
        }
    }

    // ------------------------------------------------------------------ search / outline / summary

    fun search(query: String): Flow<Pair<Int, List<SearchHit>>> = flow {
        for (i in 0 until pageCount) {
            val found = runCatching { page(i).search(query) }.getOrNull()
            val hits = found?.map { quads ->
                val r = quads.map { it.toRect() }.reduce { acc, q -> Rect(acc).apply { union(q) } }
                val box = boxes[i]
                val line = runCatching {
                    stext(i).copy(Point(box.x0, r.y0 + 1f), Point(box.x0 + box.w, r.y1 - 1f))
                }.getOrDefault("").replace(Regex("\\s+"), " ").trim()
                SearchHit(i, quads.toList(), snippet(line, query))
            } ?: emptyList()
            emit(i to hits)
        }
    }.flowOn(dispatcher)

    private fun snippet(line: String, q: String): String {
        if (line.length <= 110) return line
        val at = line.indexOf(q, ignoreCase = true).coerceAtLeast(0)
        val start = (at - 45).coerceAtLeast(0)
        val end = (at + q.length + 55).coerceAtMost(line.length)
        return (if (start > 0) "…" else "") + line.substring(start, end) + (if (end < line.length) "…" else "")
    }

    suspend fun outline(): List<OutlineItem> = mu {
        val out = mutableListOf<OutlineItem>()
        fun walk(items: Array<Outline>?, level: Int) {
            items?.forEach { o ->
                val pg = runCatching { doc.pageNumberFromLocation(doc.resolveLink(o)) }.getOrDefault(-1)
                out += OutlineItem(o.title ?: "", level, pg)
                walk(o.down, level + 1)
            }
        }
        runCatching { walk(doc.loadOutline(), 0) }
        out
    }

    /** Every highlight / note / drawing in the document, in page order. */
    suspend fun summary(): List<AnnotInfo> = mu {
        val p = pdf ?: return@mu emptyList()
        val out = mutableListOf<AnnotInfo>()
        for (i in 0 until pageCount) {
            val annots = runCatching { p.findPage(i).get("Annots") }.getOrNull()
            if (annots == null || annots.isNull) continue
            val list = pdfPage(i)?.annotations ?: continue
            list.forEachIndexed { idx, a ->
                // boxes and arrows are layout marks, not something to revise from
                val k = kindOf(a.type)
                if (a.type !in ignoredTypes && k != AnnotKind.Other && k != AnnotKind.Box && k != AnnotKind.Arrow) {
                    out += info(i, idx, a)
                }
            }
        }
        out
    }

    suspend fun close() {
        runCatching { save() }
        mu {
            dropCaches()
            runCatching { doc.destroy() }
        }
        dispatcher.close()
    }

    companion object {
        sealed interface Opened
        data class Ok(val session: PdfSession) : Opened
        data class Locked(val session: PdfSession) : Opened
        data class Failed(val message: String) : Opened

        suspend fun open(path: String): Opened {
            val d = Executors.newSingleThreadExecutor { r -> Thread(r, "mupdf").apply { priority = Thread.NORM_PRIORITY + 1 } }
                .asCoroutineDispatcher()
            return try {
                withContext(d) {
                    val doc = Document.openDocument(path)
                    val s = PdfSession(path, doc, d)
                    if (doc.needsPassword()) Locked(s) else { s.prepare(); Ok(s) }
                }
            } catch (e: Throwable) {
                d.close()
                Failed(e.message ?: "Could not open this file")
            }
        }

        fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

        fun argb(f: FloatArray?): Int {
            if (f == null || f.isEmpty()) return 0xFFFFD95A.toInt()
            fun b(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return when (f.size) {
                1 -> 0xFF shl 24 or (b(f[0]) shl 16) or (b(f[0]) shl 8) or b(f[0])
                4 -> { // CMYK
                    val r = (1 - f[0]) * (1 - f[3]); val g = (1 - f[1]) * (1 - f[3]); val bl = (1 - f[2]) * (1 - f[3])
                    0xFF shl 24 or (b(r) shl 16) or (b(g) shl 8) or b(bl)
                }
                else -> 0xFF shl 24 or (b(f[0]) shl 16) or (b(f[1]) shl 8) or b(f[2])
            }
        }

        private fun nearPolyline(pts: Array<Point>, x: Float, y: Float, r: Float): Boolean {
            if (pts.isEmpty()) return false
            if (pts.size == 1) return hypot(pts[0].x - x, pts[0].y - y) <= r
            for (k in 0 until pts.size - 1) {
                val a = pts[k]; val b = pts[k + 1]
                val dx = b.x - a.x; val dy = b.y - a.y
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
                if (hypot(a.x + t * dx - x, a.y + t * dy - y) <= r) return true
            }
            return false
        }
    }
}
