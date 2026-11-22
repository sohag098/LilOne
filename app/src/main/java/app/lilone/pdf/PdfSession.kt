package app.lilone.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
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

/** Marks on typed text. 0 means "none". */
data class TextMarks(val highlight: Int = 0, val underline: Int = 0, val strike: Int = 0, val underlineWeight: Float = 1f) {
    val any get() = highlight != 0 || underline != 0 || strike != 0
}

data class AnnotInfo(
    val page: Int,
    val index: Int,
    val kind: AnnotKind,
    /** Text: the letter colour. Note: the note's colour. Others: the annotation colour. */
    val color: Int,
    val contents: String,
    val bounds: RectF,
    /** Only for typed text (kind == Text). */
    val marks: TextMarks? = null,
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
        thickUnderline(a)
        restyleFreeText(a)
        noteIcon(a)
        pdfPage(i)?.update()
    }

    // ------------------------------------------------------------------ typed text and notes
    //
    // Typed text and sticky notes are FreeText annotations. MuPDF draws the letters; we add our own
    // background (sticky note, highlight) and lines (underline, strike) around them. Their /Rect is the
    // outer box, the letters sit inside it with a small padding, so marking text never moves it.

    private val helv by lazy { Font("Helvetica") }

    private fun advance(c: Char): Float {
        val g = runCatching { helv.encodeCharacter(c.code) }.getOrDefault(0)
        return if (g == 0 && c != ' ') 0.55f else runCatching { helv.advanceGlyph(g) }.getOrDefault(0.55f)
    }

    /** Line widths, wrapped exactly the way MuPDF wraps FreeText (break at spaces, keep newlines). */
    private fun wrapLines(text: String, size: Float, maxW: Float): List<Float> {
        val out = mutableListOf<Float>()
        val n = text.length
        var a = 0
        while (a < n) {
            var x = 0f; var space = -1; var spaceX = 0f
            var end = n; var w = -1f
            var k = a
            while (k < n) {
                val c = text[k]
                if (c == '\n' || c == '\r') { end = k + 1; w = x; break }
                if (c == ' ') { space = k + 1; spaceX = x }
                x += advance(c) * size
                if (space >= 0 && x > maxW) { end = space; w = spaceX; break }
                k++
            }
            if (w < 0f) { end = n; w = x }
            out += w
            a = end
        }
        if (out.isEmpty()) out += 0f
        return out
    }

    private fun isNoteCard(a: PDFAnnotation) =
        a.type == PDFAnnotation.TYPE_FREE_TEXT && runCatching { a.getObject().get(NOTE_KEY).asBoolean() }.getOrDefault(false)

    /** True for FreeText made (or marked) by Lil One: /Rect is the padded outer box. */
    private fun isManaged(a: PDFAnnotation) = isNoteCard(a) ||
        (a.type == PDFAnnotation.TYPE_FREE_TEXT && runCatching { a.getObject().get(TEXT_KEY).asBoolean() }.getOrDefault(false))

    private fun pads(size: Float, note: Boolean) = if (note) NOTE_PAD to NOTE_PAD else size * 0.25f to size * 0.15f

    private fun fontSize(a: PDFAnnotation) = runCatching { a.defaultAppearance?.size }.getOrNull()?.takeIf { it > 0 } ?: 12f

    private fun colorKey(a: PDFAnnotation, key: String): FloatArray? = runCatching {
        val o = a.getObject().get(key)
        if (!o.isArray || o.size() < 3) null else floatArrayOf(o.get(0).asFloat(), o.get(1).asFloat(), o.get(2).asFloat())
    }.getOrNull()

    private fun putColorKey(a: PDFAnnotation, key: String, color: Int) {
        val obj = a.getObject()
        if (color == 0) { obj.delete(key); return }
        val arr = pdf!!.newArray()
        rgb(color).forEach { arr.push(it) }
        obj.put(key, arr)
    }

    private fun marksOf(a: PDFAnnotation) = TextMarks(
        highlight = colorKey(a, HL_KEY)?.let { argb(it) } ?: 0,
        underline = colorKey(a, UL_KEY)?.let { argb(it) } ?: 0,
        strike = colorKey(a, ST_KEY)?.let { argb(it) } ?: 0,
        underlineWeight = runCatching { a.getObject().get(ULW_KEY).asFloat() }.getOrDefault(0f).takeIf { it > 0f } ?: 1f,
    )

    /**
     * Outer box for typed text or a note whose top-left corner is at (x, y), kept inside the page.
     * The letters get exactly the width MuPDF needs, so they wrap the same way it wraps them.
     */
    private fun textBoxRect(box: PageBox, x: Float, y: Float, text: String, size: Float, note: Boolean): Rect {
        val (px, py) = pads(size, note)
        val maxInner = if (note) NOTE_MAX_W else max(60f, box.x0 + box.w - x - 6f - 2 * px)
        val natural = wrapLines(text, size, Float.MAX_VALUE).max()
        val w = min(maxInner, max(size * 2f, natural + size * 0.3f))
        val lines = wrapLines(text, size, w).size
        val ow = w + 2 * px
        val oh = lines * size * 1.2f + size * 0.25f + 2 * py
        val ox = x.coerceIn(box.x0, max(box.x0, box.x0 + box.w - ow))
        val oy = y.coerceIn(box.y0, max(box.y0, box.y0 + box.h - oh))
        return Rect(ox, oy, ox + ow, oy + oh)
    }

    private fun rawRect(o: com.artifex.mupdf.fitz.PDFObject): Rect {
        val a = o.get(0).asFloat(); val b = o.get(1).asFloat(); val c = o.get(2).asFloat(); val d = o.get(3).asFloat()
        return Rect(min(a, c), min(b, d), max(a, c), max(b, d))
    }

    private fun fill(c: FloatArray) = "${c[0]} ${c[1]} ${c[2]} rg\n"

    /** Draws the sticky note / highlight / lines for managed FreeText. MuPDF still draws the letters. */
    private fun restyleFreeText(a: PDFAnnotation) {
        if (!isManaged(a)) return
        runCatching {
            val obj = a.getObject()
            val note = isNoteCard(a)
            val size = fontSize(a)
            val (px, py) = pads(size, note)
            val outerRaw = rawRect(obj.get("Rect"))
            val outer = a.rect
            val noteColor = if (note) colorKey(a, HL_KEY) ?: rgb(NOTE_DEFAULT) else null
            // let MuPDF lay out only the letters, in the inner box, with no background of its own
            obj.delete("C")
            a.setRect(Rect(outer.x0 + px, outer.y0 + py, outer.x1 - px, outer.y1 - py))
            a.update()
            val n = obj.get("AP").get("N")
            val inner = rawRect(n.get("BBox"))
            val letters = n.readStream()
            val res = n.get("Resources")

            val sb = StringBuilder("q\n")
            val x0 = outerRaw.x0; val y0 = outerRaw.y0; val x1 = outerRaw.x1; val y1 = outerRaw.y1
            if (noteColor != null) {
                // sticky note with the bottom-right corner folded up
                val f = NOTE_FOLD
                val dark = noteColor.map { it * 0.78f }.toFloatArray()
                sb.append(fill(noteColor))
                sb.append("$x0 $y1 m $x1 $y1 l $x1 ${y0 + f} l ${x1 - f} $y0 l $x0 $y0 l h f\n")
                sb.append(fill(dark))
                sb.append("$x1 ${y0 + f} m ${x1 - f} ${y0 + f} l ${x1 - f} $y0 l h f\n")
                sb.append("${dark[0]} ${dark[1]} ${dark[2]} RG 0.6 w\n")
                sb.append("$x0 $y1 m $x1 $y1 l $x1 ${y0 + f} l ${x1 - f} $y0 l $x0 $y0 l h S\n")
            } else {
                val m = marksOf(a)
                val widths = wrapLines(a.contents ?: "", size, inner.x1 - inner.x0)
                widths.forEachIndexed { k, w ->
                    if (w <= 0f) return@forEachIndexed
                    val base = inner.y1 - size * 0.8f - k * size * 1.2f
                    if (m.highlight != 0) {
                        val hx = size * 0.12f
                        sb.append(fill(rgb(m.highlight)))
                        sb.append("${inner.x0 - hx} ${base - size * 0.28f} ${w + 2 * hx} ${size * 1.2f} re f\n")
                    }
                }
                widths.forEachIndexed { k, w ->
                    if (w <= 0f) return@forEachIndexed
                    val base = inner.y1 - size * 0.8f - k * size * 1.2f
                    if (m.underline != 0) {
                        val t = size / 15f * m.underlineWeight
                        sb.append(fill(rgb(m.underline)))
                        sb.append("${inner.x0} ${base - size * 0.12f - t / 2} $w $t re f\n")
                    }
                    if (m.strike != 0) {
                        val t = size / 14f
                        sb.append(fill(rgb(m.strike)))
                        sb.append("${inner.x0} ${base + size * 0.28f - t / 2} $w $t re f\n")
                    }
                }
            }
            sb.append("Q\n")
            val buf = Buffer()
            buf.writeLine(sb.toString())
            buf.writeBytes(letters)
            val bbox = Rect(x0, y0, x1, y1)
            obj.put("Rect", bbox)
            if (noteColor != null) { // other PDF apps show the note colour as the box background
                val arr = pdf!!.newArray(); noteColor.forEach { arr.push(it) }; obj.put("C", arr)
            }
            a.setAppearance(bbox, res, buf)
        }
    }

    /** A clean sticky-note icon for pinned notes (instead of MuPDF's outlined square). */
    private fun noteIcon(a: PDFAnnotation) {
        if (a.type != PDFAnnotation.TYPE_TEXT) return
        runCatching {
            val obj = a.getObject()
            val r = rawRect(obj.get("Rect"))
            val c = a.color?.takeIf { it.size >= 3 } ?: rgb(NOTE_DEFAULT)
            val dark = c.map { it * 0.72f }.toFloatArray()
            val s = min(r.x1 - r.x0, r.y1 - r.y0)
            val x0 = r.x0 + 1; val y0 = r.y1 - s + 1; val x1 = r.x0 + s - 1; val y1 = r.y1 - 1
            val f = s * 0.3f
            val sb = StringBuilder("q\n")
            sb.append(fill(c))
            sb.append("$x0 $y1 m $x1 $y1 l $x1 ${y0 + f} l ${x1 - f} $y0 l $x0 $y0 l h f\n")
            sb.append(fill(dark))
            sb.append("$x1 ${y0 + f} m ${x1 - f} ${y0 + f} l ${x1 - f} $y0 l h f\n")
            sb.append("${dark[0]} ${dark[1]} ${dark[2]} RG 0.7 w\n")
            sb.append("$x0 $y1 m $x1 $y1 l $x1 ${y0 + f} l ${x1 - f} $y0 l $x0 $y0 l h S\n")
            val lw = s * 0.07f
            for (k in 1..3) {
                val y = y1 - s * (0.25f + k * 0.15f)
                val end = if (k == 3) x1 - f - lw else x1 - s * 0.18f
                sb.append("${x0 + s * 0.18f} ${y - lw / 2} ${end - x0 - s * 0.18f} $lw re f\n")
            }
            sb.append("Q\n")
            val buf = Buffer(); buf.writeLine(sb.toString())
            a.setAppearance(r, null, buf)
        }
    }

    /**
     * MuPDF always draws underlines 1/16 of the text height thick and ignores any width. For the thicker sizes we
     * store the weight on the annotation (/LilOneW) and draw the same line ourselves, just thicker. It stays a real
     * Underline annotation (Highlights list, other PDF apps), and is redrawn after colour changes.
     */
    private fun thickUnderline(a: PDFAnnotation) {
        if (a.type != PDFAnnotation.TYPE_UNDERLINE) return
        runCatching {
            val obj = a.getObject()
            val weight = obj.get(UNDERLINE_WEIGHT).asFloat()
            if (weight <= 1.01f) return
            val qp = obj.get("QuadPoints")
            val n = qp.size()
            if (n < 8) return
            val c = a.color ?: floatArrayOf(0f, 0f, 0f)
            val sb = StringBuilder()
            when (c.size) {
                1 -> sb.append("${c[0]} G\n")
                3 -> sb.append("${c[0]} ${c[1]} ${c[2]} RG\n")
                4 -> sb.append("${c[0]} ${c[1]} ${c[2]} ${c[3]} K\n")
            }
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            var i = 0
            while (i + 7 < n) {
                // PDF quad order: UL, UR, LL, LR (PDF space, y up), same as MuPDF's own underline code
                val ulx = qp.get(i).asFloat(); val uly = qp.get(i + 1).asFloat()
                val urx = qp.get(i + 2).asFloat(); val ury = qp.get(i + 3).asFloat()
                val llx = qp.get(i + 4).asFloat(); val lly = qp.get(i + 5).asFloat()
                val lrx = qp.get(i + 6).asFloat(); val lry = qp.get(i + 7).asFloat()
                val h = hypot(ulx - llx, uly - lly)
                val w = h / 16f * weight
                // a bit lower than MuPDF's 1/7 so a thick line doesn't cover the letters
                val f = (1f / 7f) - (w / h) * 0.35f
                val ax = llx + (ulx - llx) * f; val ay = lly + (uly - lly) * f
                val bx = lrx + (urx - lrx) * f; val by = lry + (ury - lry) * f
                sb.append("$w w\n$ax $ay m\n$bx $by l\nS\n")
                for (v in floatArrayOf(ulx, urx, llx, lrx)) { x0 = min(x0, v); x1 = max(x1, v) }
                for (v in floatArrayOf(uly, ury, lly, lry)) { y0 = min(y0, v); y1 = max(y1, v) }
                val pad = w
                x0 -= pad; y0 -= pad; x1 += pad; y1 += pad
                i += 8
            }
            val bbox = Rect(x0, y0, x1, y1)
            obj.put("Rect", bbox) // raw PDF-space rect, same space as the appearance box
            a.setAppearance(bbox, null, com.artifex.mupdf.fitz.Buffer().apply { writeLine(sb.toString()) })
        }
    }

    suspend fun addMarkup(i: Int, kind: AnnotKind, quads: List<Quad>, color: Int, text: String, weight: Float = 1f) = mu {
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
            if (kind == AnnotKind.Underline && weight > 1.01f) a.getObject().put(UNDERLINE_WEIGHT, weight)
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
            a.getObject().put(TEXT_KEY, true)
            a.setContents(text)
            a.setDefaultAppearance("Helv", size, rgb(color))
            a.setBorderWidth(0f)
            a.setRect(textBoxRect(box, x, y, text, size, note = false))
            finish(i, a)
        }
    }

    /** A sticky note on the page, showing its text. Its top-left corner is at (x, y). */
    suspend fun addNoteCard(i: Int, x: Float, y: Float, text: String, color: Int) = mu {
        val p = pdfPage(i) ?: return@mu
        val box = boxes[i]
        op("Note") {
            val a = p.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
            a.getObject().put(NOTE_KEY, true)
            putColorKey(a, HL_KEY, color)
            a.setContents(text)
            a.setDefaultAppearance("Helv", NOTE_SIZE, rgb(inkOn(color)))
            a.setBorderWidth(0f)
            a.setRect(textBoxRect(box, x, y, text, NOTE_SIZE, note = true))
            finish(i, a)
        }
    }

    /** Sets highlight / underline / strike on typed text. */
    suspend fun setTextMarks(i: Int, index: Int, marks: TextMarks) = mu {
        val p = pdfPage(i) ?: return@mu
        val a = p.annotations?.getOrNull(index) ?: return@mu
        if (a.type != PDFAnnotation.TYPE_FREE_TEXT || isNoteCard(a)) return@mu
        op("Mark") {
            if (!isManaged(a)) {
                // text from an older version (or another app): grow the box by the padding so the letters stay put
                val (px, py) = pads(fontSize(a), false)
                val r = a.rect
                a.getObject().put(TEXT_KEY, true)
                a.setRect(Rect(r.x0 - px, r.y0 - py, r.x1 + px, r.y1 + py))
                if (a.color != null) a.setColor(floatArrayOf())
            }
            putColorKey(a, HL_KEY, marks.highlight)
            putColorKey(a, UL_KEY, marks.underline)
            putColorKey(a, ST_KEY, marks.strike)
            if (marks.underline != 0 && marks.underlineWeight > 1.01f) a.getObject().put(ULW_KEY, marks.underlineWeight)
            else a.getObject().delete(ULW_KEY)
            finish(i, a)
        }
    }

    /** Moves typed text or a note by (dx, dy), keeping it on the page. */
    suspend fun moveAnnot(i: Int, index: Int, dx: Float, dy: Float) = mu {
        val p = pdfPage(i) ?: return@mu
        val a = p.annotations?.getOrNull(index) ?: return@mu
        val box = boxes[i]
        val r = a.rect
        val w = r.x1 - r.x0; val h = r.y1 - r.y0
        val nx = (r.x0 + dx).coerceIn(box.x0, max(box.x0, box.x0 + box.w - w))
        val ny = (r.y0 + dy).coerceIn(box.y0, max(box.y0, box.y0 + box.h - h))
        op("Move") {
            a.setRect(Rect(nx, ny, nx + w, ny + h))
            finish(i, a)
        }
    }

    /** Typed text and notes on a page: the things that can be dragged around. */
    suspend fun movables(i: Int): List<AnnotInfo> = mu {
        val list = pdfPage(i)?.annotations ?: return@mu emptyList()
        list.mapIndexedNotNull { idx, a ->
            if (a.type == PDFAnnotation.TYPE_FREE_TEXT || a.type == PDFAnnotation.TYPE_TEXT) info(i, idx, a) else null
        }
    }

    /** The annotation drawn on its own (transparent background), [scale] pixels per point. */
    suspend fun renderAnnot(i: Int, index: Int, scale: Float): Bitmap? = mu {
        runCatching {
            val a = pdfPage(i)?.annotations?.getOrNull(index) ?: return@runCatching null
            val b = a.bounds
            val w = ((b.x1 - b.x0) * scale).toInt().coerceIn(1, 2400)
            val h = ((b.y1 - b.y0) * scale).toInt().coerceIn(1, 2400)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val dev = AndroidDrawDevice(bmp, (b.x0 * scale).toInt(), (b.y0 * scale).toInt())
            try {
                a.run(dev, Matrix(scale), null)
                dev.close()
            } finally {
                dev.destroy()
            }
            bmp
        }.getOrNull()
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

    private fun kindOf(a: PDFAnnotation) = if (isNoteCard(a)) AnnotKind.Note else kindOf(a.type)

    private fun info(i: Int, idx: Int, a: PDFAnnotation): AnnotInfo {
        val b = a.bounds
        val bounds = RectF(b.x0, b.y0, b.x1, b.y1)
        return when {
            isNoteCard(a) -> AnnotInfo(i, idx, AnnotKind.Note, argb(colorKey(a, HL_KEY) ?: rgb(NOTE_DEFAULT)), a.contents ?: "", bounds)
            a.type == PDFAnnotation.TYPE_FREE_TEXT -> {
                val ink = runCatching { a.defaultAppearance?.color }.getOrNull()
                AnnotInfo(i, idx, AnnotKind.Text, argb(ink ?: floatArrayOf(0f)), a.contents ?: "", bounds, marksOf(a))
            }
            else -> AnnotInfo(i, idx, kindOf(a.type), argb(a.color), a.contents ?: "", bounds)
        }
    }

    suspend fun updateAnnot(i: Int, index: Int, contents: String?, color: Int?) = mu {
        val p = pdfPage(i) ?: return@mu
        val a = p.annotations?.getOrNull(index) ?: return@mu
        op("Edit") {
            val freeText = a.type == PDFAnnotation.TYPE_FREE_TEXT
            val note = isNoteCard(a)
            if (color != null) when {
                note -> {
                    putColorKey(a, HL_KEY, color)
                    a.setDefaultAppearance("Helv", fontSize(a), rgb(inkOn(color)))
                }
                freeText -> a.setDefaultAppearance(a.defaultAppearance?.font ?: "Helv", fontSize(a), rgb(color))
                else -> a.setColor(rgb(color))
            }
            if (contents != null) {
                a.setContents(contents)
                if (freeText) {
                    val r = a.rect
                    if (isManaged(a)) {
                        a.setRect(textBoxRect(boxes[i], r.x0, r.y0, contents, fontSize(a), note))
                    } else {
                        // older text: no padding around it, keep it that way
                        val g = textBoxRect(boxes[i], r.x0, r.y0, contents, fontSize(a), false)
                        val (px, py) = pads(fontSize(a), false)
                        a.setRect(Rect(g.x0, g.y0, g.x1 - 2 * px, g.y1 - 2 * py))
                    }
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
                val k = kindOf(a)
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

        const val UNDERLINE_WEIGHT = "LilOneW"
        // keys Lil One keeps on its own FreeText annotations
        const val TEXT_KEY = "LilOneT"
        const val NOTE_KEY = "LilOneNote"
        const val HL_KEY = "LilOneHL"
        const val UL_KEY = "LilOneUL"
        const val ULW_KEY = "LilOneULW"
        const val ST_KEY = "LilOneST"
        const val NOTE_SIZE = 10f
        const val NOTE_PAD = 6f
        const val NOTE_FOLD = 7f
        const val NOTE_MAX_W = 170f
        const val NOTE_DEFAULT = 0xFFFFD95A.toInt()

        /** Dark letters on light colours, white on dark ones. */
        fun inkOn(c: Int): Int {
            val l = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            return if (l < 140f) 0xFFFFFFFF.toInt() else 0xFF222222.toInt()
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
