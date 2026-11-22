package app.lilone.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lilone.data.Importer
import app.lilone.data.LibraryRepo
import app.lilone.pdf.AnnotInfo
import app.lilone.pdf.AnnotKind
import app.lilone.pdf.OutlineItem
import app.lilone.pdf.PageBox
import app.lilone.pdf.PdfSession
import app.lilone.pdf.SearchHit
import com.artifex.mupdf.fitz.Quad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

enum class Tool(val drags: Boolean, val label: String) {
    Select(false, "Select"),
    AskAI(true, "Ask AI"),
    Highlight(true, "Highlight"),
    Underline(true, "Underline"),
    Strike(true, "Strike"),
    Pen(true, "Pen"),
    Marker(true, "Marker"),
    Text(false, "Text"),
    Note(false, "Note"),
    Box(true, "Box"),
    Arrow(true, "Arrow"),
    Eraser(true, "Eraser");

    val hasColor get() = this != Select && this != Eraser && this != AskAI
    val hasSize get() = this == Pen || this == Marker || this == Box || this == Arrow || this == Text || this == Underline
}

enum class ReadMode { Day, Sepia, Night }
enum class Panel { Contents, Bookmarks, Notes, Search, AI }

/** Highlighter-friendly colours first, then pen colours. */
val Palette = listOf(
    0xFFFFD95A, 0xFF8CE99A, 0xFF74C0FC, 0xFFF783AC, 0xFFFFA94D, 0xFFB197FC, 0xFFE03131, 0xFF1C1B1F,
).map { it.toInt() }

/** Shown in the colour picker and in the notes summary. */
val PaletteNames = listOf("Yellow", "Green", "Blue", "Pink", "Orange", "Purple", "Red", "Black")

data class TextDraft(val page: Int, val at: PointF, val isNote: Boolean)

/** Process-wide scope for work that must outlive a screen (saving when the reader closes). */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)

private class Rendered(val bitmap: ImageBitmap, val width: Int, val version: Int, val bytes: Int)

class ReaderViewModel(app: Application, val docId: String) : AndroidViewModel(app) {
    private val repo = LibraryRepo.get(app)
    val doc get() = repo.state.value.docs.firstOrNull { it.id == docId }

    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var locked by mutableStateOf(false); private set
    var boxes by mutableStateOf<List<PageBox>>(emptyList()); private set
    val initialPage = doc?.lastPage ?: 0
    var currentPage by mutableIntStateOf(initialPage); private set

    private var session: PdfSession? = null
    internal val pdf: PdfSession? get() = session

    // ---- rendering
    private val versions = mutableStateMapOf<Int, Int>()
    fun version(i: Int) = versions[i] ?: 0
    private val maxCache = (Runtime.getRuntime().maxMemory() / 3).coerceAtMost(360L * 1024 * 1024).toInt()
    private val cache = object : LruCache<Int, Rendered>(maxCache) {
        override fun sizeOf(key: Int, value: Rendered) = value.bytes
    }

    // ---- view state
    var readMode by mutableStateOf(ReadMode.Day)
    var chromeVisible by mutableStateOf(true)
    var panel by mutableStateOf<Panel?>(null)
    var zoom by mutableStateOf(1f)
    var offsetX by mutableStateOf(0f)

    // ---- edit state
    var editMode by mutableStateOf(false); private set
    var tool by mutableStateOf(Tool.Highlight)
    private val colors = mutableStateMapOf(
        Tool.Highlight to Palette[0], Tool.Underline to Palette[6], Tool.Strike to Palette[6],
        Tool.Pen to Palette[2], Tool.Marker to Palette[0], Tool.Text to Palette[7],
        Tool.Note to Palette[0], Tool.Box to Palette[6], Tool.Arrow to Palette[6],
    )
    private val sizes = mutableStateMapOf(Tool.Pen to 1, Tool.Marker to 1, Tool.Box to 1, Tool.Arrow to 1, Tool.Text to 1, Tool.Underline to 0)
    fun colorOf(t: Tool) = colors[t] ?: Palette[0]
    fun setColor(c: Int) { colors[tool] = c }
    fun sizeOf(t: Tool) = sizes[t] ?: 1
    fun setSize(s: Int) { sizes[tool] = s }
    var stylusOnly by mutableStateOf(false)
    private var stylusAutoSet = false
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    fun strokeWidth(t: Tool, s: Int = sizeOf(t)) = when (t) {
        Tool.Marker -> listOf(7f, 11f, 16f)[s]
        Tool.Pen -> listOf(1.2f, 2f, 3.4f)[s]
        else -> listOf(1f, 1.8f, 3f)[s]
    }
    fun textSize(s: Int = sizeOf(Tool.Text)) = listOf(10f, 14f, 20f)[s]
    /** Underline thickness: thin (the standard line), medium, thick. */
    fun underlineWeight(s: Int = sizeOf(Tool.Underline)) = listOf(1f, 2f, 3.5f)[s]

    // ---- live overlays (PDF point coordinates)
    var strokePage by mutableIntStateOf(-1); private set
    val stroke = mutableStateListOf<PointF>()
    var activeTool by mutableStateOf(tool); private set
    var selPage by mutableIntStateOf(-1); private set
    var selQuads by mutableStateOf<List<Quad>>(emptyList()); private set
    var shapePage by mutableIntStateOf(-1); private set
    var shapeA by mutableStateOf(PointF()); private set
    var shapeB by mutableStateOf(PointF()); private set

    private var downPage = -1
    private var downPt = PointF()
    private var selJob: Job? = null
    private var eraseJob: Job? = null

    // ---- dialogs / panels
    var textDraft by mutableStateOf<TextDraft?>(null)
    var selected by mutableStateOf<AnnotInfo?>(null)
    var outline by mutableStateOf<List<OutlineItem>?>(null); private set
    fun cacheOutline(o: List<OutlineItem>) { if (outline == null) outline = o }
    var notes by mutableStateOf<List<AnnotInfo>?>(null); private set
    var searchQuery by mutableStateOf("")
    val searchResults = mutableStateListOf<SearchHit>()
    var searching by mutableStateOf(false); private set
    var searchedPages by mutableIntStateOf(0); private set
    var activeHit by mutableStateOf<SearchHit?>(null)
    private var searchJob: Job? = null

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    // ---- study assistant (Gemini)
    var showSettings by mutableStateOf(false)
    /** Bumped when Settings closes so the assistant panel re-checks for a key. */
    var keyVersion by mutableIntStateOf(0)
    val ai = AiAssistant(this)
    private var aiNotes = 0

    fun addAiNote(page: Int, text: String) {
        val s = session ?: return
        val box = boxes.getOrNull(page) ?: return
        val n = aiNotes++
        viewModelScope.launch {
            s.addNote(page, box.x0 + box.w - 22f, box.y0 + 26f + (n % 12) * 26f, text, Palette[5])
            edited(page)
            messages.tryEmit("Saved as a note on page ${page + 1}")
        }
    }
    val scrollTo = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    private var saveJob: Job? = null
    private var progressJob: Job? = null
    private var page0Dirty = false

    init {
        viewModelScope.launch {
            repo.markOpened(docId)
            when (val r = PdfSession.open(repo.file(docId).path)) {
                is PdfSession.Companion.Ok -> ready(r.session)
                is PdfSession.Companion.Locked -> { session = r.session; locked = true; loading = false }
                is PdfSession.Companion.Failed -> { error = r.message; loading = false }
            }
        }
    }

    private suspend fun ready(s: PdfSession) {
        session = s
        boxes = s.boxes
        currentPage = initialPage.coerceIn(0, (s.pageCount - 1).coerceAtLeast(0))
        locked = false
        loading = false
        refreshHistory()
        repo.setProgress(docId, currentPage, s.pageCount)
    }

    fun unlock(password: String) = viewModelScope.launch {
        val s = session ?: return@launch
        if (s.authenticate(password)) ready(s) else messages.tryEmit("Wrong password")
    }

    // ------------------------------------------------------------ rendering

    fun cached(i: Int): ImageBitmap? = cache.get(i)?.bitmap

    suspend fun render(i: Int, width: Int): ImageBitmap? {
        val v = version(i)
        cache.get(i)?.let { if (it.width == width && it.version == v) return it.bitmap }
        val s = session ?: return null
        val bmp: Bitmap = s.render(i, width) ?: return null
        val r = Rendered(bmp.asImageBitmap(), width, v, bmp.byteCount)
        cache.put(i, r)
        return r.bitmap
    }

    private fun invalidate(i: Int) { versions[i] = version(i) + 1 }
    private fun invalidateAll() { boxes.indices.forEach { invalidate(it) } }

    // ------------------------------------------------------------ navigation

    fun onPageChanged(p: Int) {
        if (p == currentPage) return
        currentPage = p
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(1200)
            repo.setProgress(docId, p, boxes.size)
        }
    }

    fun jumpTo(page: Int) {
        if (page in boxes.indices) scrollTo.tryEmit(page)
    }

    fun toggleBookmark() = repo.toggleBookmark(docId, currentPage)

    // ------------------------------------------------------------ edit mode

    fun toggleEdit() {
        editMode = !editMode
        if (!editMode) { clearOverlays(); saveSoon(0) } else chromeVisible = true
    }

    fun onStylusSeen() {
        if (!stylusAutoSet) {
            stylusAutoSet = true
            if (!stylusOnly) {
                stylusOnly = true
                messages.tryEmit("Stylus detected: drawing with the pen only, fingers scroll")
            }
        }
    }

    fun toolDown(page: Int, p: PointF, eraserTip: Boolean, radiusPt: Float) {
        val t = if (eraserTip) Tool.Eraser else tool
        activeTool = t
        downPage = page
        downPt = p
        when (t) {
            Tool.Highlight, Tool.Underline, Tool.Strike -> {
                selPage = page; updateSelection(page, p, p)
            }
            Tool.Pen, Tool.Marker -> { stroke.clear(); stroke.add(p); strokePage = page }
            Tool.Box, Tool.Arrow, Tool.AskAI -> { shapePage = page; shapeA = p; shapeB = p }
            Tool.Eraser -> erase(page, p, radiusPt)
            else -> {}
        }
    }

    fun toolMove(p: PointF, radiusPt: Float) {
        when (activeTool) {
            Tool.Highlight, Tool.Underline, Tool.Strike -> updateSelection(downPage, downPt, p)
            Tool.Pen, Tool.Marker -> {
                val last = stroke.lastOrNull()
                if (last == null || hypot(last.x - p.x, last.y - p.y) > 0.6f) stroke.add(p)
            }
            Tool.Box, Tool.Arrow, Tool.AskAI -> shapeB = p
            Tool.Eraser -> erase(downPage, p, radiusPt)
            else -> {}
        }
    }

    fun toolCancel() = clearOverlays()

    fun toolUp(p: PointF, radiusPt: Float) {
        val s = session ?: return clearOverlays()
        val page = downPage
        val t = activeTool
        val moved = hypot(p.x - downPt.x, p.y - downPt.y)
        when (t) {
            Tool.Highlight, Tool.Underline, Tool.Strike -> {
                if (moved < 2f) { clearOverlays(); tap(page, p, radiusPt); return }
                val a = downPt; val color = colorOf(t)
                viewModelScope.launch {
                    val quads = s.selectionQuads(page, a, p)
                    if (quads.isEmpty()) {
                        messages.tryEmit("No selectable text here. For scanned pages, use the Marker.")
                    } else {
                        val text = s.selectionText(page, a, p)
                        val kind = when (t) { Tool.Underline -> AnnotKind.Underline; Tool.Strike -> AnnotKind.Strike; else -> AnnotKind.Highlight }
                        s.addMarkup(page, kind, quads, color, text, if (t == Tool.Underline) underlineWeight() else 1f)
                        edited(page)
                    }
                    clearOverlays()
                }
            }
            Tool.Pen, Tool.Marker -> {
                val pts = stroke.toList().ifEmpty { listOf(p) }
                val points = if (pts.size == 1) listOf(pts[0], PointF(pts[0].x + 0.5f, pts[0].y + 0.5f)) else pts
                val color = colorOf(t); val w = strokeWidth(t); val op = if (t == Tool.Marker) 0.38f else 1f
                viewModelScope.launch {
                    s.addInk(page, listOf(points), color, w, op)
                    edited(page)
                    clearOverlays()
                }
            }
            Tool.Box -> {
                val r = RectF(minOf(shapeA.x, p.x), minOf(shapeA.y, p.y), maxOf(shapeA.x, p.x), maxOf(shapeA.y, p.y))
                if (r.width() < 3f || r.height() < 3f) return clearOverlays()
                val color = colorOf(t); val w = strokeWidth(t)
                viewModelScope.launch { s.addBox(page, r, color, w); edited(page); clearOverlays() }
            }
            Tool.AskAI -> {
                val r = RectF(minOf(shapeA.x, p.x), minOf(shapeA.y, p.y), maxOf(shapeA.x, p.x), maxOf(shapeA.y, p.y))
                clearOverlays()
                if (r.width() < 8f || r.height() < 8f) {
                    messages.tryEmit("Drag a box around the text or diagram you want explained")
                    return
                }
                ai.explainRegion(page, r)
                openPanel(Panel.AI)
            }
            Tool.Arrow -> {
                if (moved < 6f) return clearOverlays()
                val a = shapeA; val color = colorOf(t); val w = strokeWidth(t)
                viewModelScope.launch { s.addArrow(page, a, p, color, w); edited(page); clearOverlays() }
            }
            else -> clearOverlays()
        }
    }

    private fun updateSelection(page: Int, a: PointF, b: PointF) {
        val s = session ?: return
        selJob?.cancel()
        selJob = viewModelScope.launch {
            val q = s.selectionQuads(page, a, b)
            if (selPage == page) selQuads = q
        }
    }

    private fun erase(page: Int, p: PointF, r: Float) {
        val s = session ?: return
        if (eraseJob?.isActive == true) return
        eraseJob = viewModelScope.launch {
            if (s.eraseAt(page, p.x, p.y, r)) edited(page)
        }
    }

    private fun clearOverlays() {
        selJob?.cancel()
        stroke.clear(); strokePage = -1
        selQuads = emptyList(); selPage = -1
        shapePage = -1
    }

    /** A tap on a page (finger or pen, no drag). */
    fun tap(page: Int, p: PointF, radiusPt: Float) {
        if (editMode && (tool == Tool.Text || tool == Tool.Note)) {
            textDraft = TextDraft(page, p, isNote = tool == Tool.Note)
            return
        }
        val s = session ?: return
        viewModelScope.launch {
            val hit = s.hitTest(page, p.x, p.y, radiusPt)
            when {
                hit != null -> selected = hit
                !editMode -> chromeVisible = !chromeVisible
            }
        }
    }

    fun commitText(draft: TextDraft, text: String) {
        textDraft = null
        if (text.isBlank()) return
        val s = session ?: return
        viewModelScope.launch {
            if (draft.isNote) s.addNote(draft.page, draft.at.x, draft.at.y, text.trim(), colorOf(Tool.Note))
            else s.addFreeText(draft.page, draft.at.x, draft.at.y, text.trimEnd(), textSize(), colorOf(Tool.Text))
            edited(draft.page)
        }
    }

    fun updateSelected(contents: String? = null, color: Int? = null) {
        val a = selected ?: return
        val s = session ?: return
        viewModelScope.launch {
            s.updateAnnot(a.page, a.index, contents, color)
            selected = a.copy(contents = contents ?: a.contents, color = color ?: a.color)
            edited(a.page)
        }
    }

    fun deleteSelected() {
        val a = selected ?: return
        val s = session ?: return
        selected = null
        viewModelScope.launch { s.deleteAnnot(a.page, a.index); edited(a.page) }
    }

    fun undo() = viewModelScope.launch { session?.undo(); invalidateAll(); afterHistoryChange() }
    fun redo() = viewModelScope.launch { session?.redo(); invalidateAll(); afterHistoryChange() }

    private suspend fun afterHistoryChange() {
        page0Dirty = true
        refreshHistory()
        if (panel == Panel.Notes) loadNotes()
        saveSoon()
    }

    private suspend fun edited(page: Int) {
        invalidate(page)
        if (page == 0) page0Dirty = true
        refreshHistory()
        if (panel == Panel.Notes) loadNotes()
        saveSoon()
    }

    private suspend fun refreshHistory() {
        val h = session?.history() ?: return
        canUndo = h.canUndo; canRedo = h.canRedo
    }

    private fun saveSoon(delayMs: Long = 5000) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(delayMs)
            if (session?.save() == false) messages.tryEmit("Couldn't save changes. Try again.")
        }
    }

    fun saveNow() {
        val s = session ?: return
        AppScope.launch { s.save() }
        repo.setProgress(docId, currentPage, boxes.size)
    }

    // ------------------------------------------------------------ panels

    fun openPanel(p: Panel) {
        panel = p
        when (p) {
            Panel.Contents -> if (outline == null) viewModelScope.launch { outline = session?.outline() ?: emptyList() }
            Panel.Notes -> viewModelScope.launch { loadNotes() }
            else -> {}
        }
    }

    private suspend fun loadNotes() { notes = session?.summary() ?: emptyList() }

    fun search(q: String) {
        searchQuery = q
        searchJob?.cancel()
        searchResults.clear(); activeHit = null; searchedPages = 0
        val s = session ?: return
        if (q.isBlank()) { searching = false; return }
        searching = true
        searchJob = viewModelScope.launch {
            s.search(q.trim()).collect { (i, hits) ->
                searchResults.addAll(hits)
                searchedPages = i + 1
            }
            searching = false
        }
    }

    fun showHit(h: SearchHit) { activeHit = h; jumpTo(h.page) }

    // ------------------------------------------------------------ zoom

    fun updateZoom(z: Float) { zoom = z.coerceIn(1f, 5f) }

    override fun onCleared() {
        val s = session ?: return
        val dirty = page0Dirty
        val file = repo.file(docId); val thumb = repo.thumb(docId)
        repo.setProgress(docId, currentPage, boxes.size)
        AppScope.launch {
            s.close()
            if (dirty) Importer.refreshThumb(file, thumb)
            // a new timestamp makes the library reload the (possibly updated) thumbnail
            repo.updateDoc(docId) { it.copy(lastOpened = System.currentTimeMillis()) }
        }
        cache.evictAll()
    }

    /** Saves, then copies the PDF to a shareable file named after the document. */
    suspend fun exportCopy(): java.io.File {
        session?.save()
        return app.lilone.data.Sharing.shareableCopy(getApplication(), repo.file(docId), doc?.title ?: "Document")
    }

    fun notesAsText(): String {
        val list = notes ?: return ""
        val sb = StringBuilder("${doc?.title ?: "Document"}: my notes\n")
        list.groupBy { it.page }.forEach { (page, items) ->
            sb.append("\nPage ${page + 1}\n")
            items.forEach { a ->
                val text = a.contents.ifBlank { a.kind.name.lowercase() }
                sb.append("• [${colorName(a.color)}] $text\n")
            }
        }
        return sb.toString()
    }
}

/** Index of the palette colour closest to [c] (PDF colours round-trip through floats). */
fun paletteIndex(c: Int): Int {
    fun d(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }
    val best = Palette.indices.minBy { d(Palette[it], c) }
    return if (d(Palette[best], c) < 40 * 40) best else -1
}

fun colorName(c: Int): String = paletteIndex(c).let { if (it >= 0) PaletteNames[it] else "Other" }
