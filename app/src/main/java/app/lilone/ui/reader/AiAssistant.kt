package app.lilone.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import app.lilone.data.Gemini
import app.lilone.data.GeminiError
import app.lilone.data.GeminiSettings
import app.lilone.data.Part
import app.lilone.data.Turn
import app.lilone.pdf.AnnotKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

data class QuizQ(val question: String, val options: List<String>, val answer: Int, val explanation: String, val page: Int?)
data class Flashcard(val front: String, val back: String, val page: Int?)

data class AiMessage(
    val id: Long,
    val user: Boolean,
    val text: String,
    val page: Int,
    val image: ImageBitmap? = null,
    val quiz: List<QuizQ>? = null,
    val cards: List<Flashcard>? = null,
    val pending: Boolean = false,
    val error: Boolean = false,
    val saved: Boolean = false,
)

enum class AiScope(val label: String) { Page("This page"), Chapter("This chapter"), Document("Whole PDF") }
enum class QuizSource(val label: String) { Page("This page"), Chapter("This chapter"), Highlights("My highlights") }

private const val SYSTEM = """You are the study assistant inside Lil One, a PDF study app used by students.
- Be accurate, clear and exam-focused. Prefer short paragraphs and bullet points.
- Use simple Markdown only: **bold**, bullet lists, numbered lists and short headings.
- When you use the student's document, cite pages like (p. 12).
- If the document doesn't contain the answer, say so briefly, then answer from standard knowledge of the subject and label that part "General knowledge".
- Explain jargon the first time you use it. Add a brief clinical example or mnemonic when it genuinely helps memory.
- Write formulas and numbers in plain text (e.g. SV = EDV − ESV = 120 − 50 = 70 mL). Never use LaTeX or $ signs for maths.
- Never invent page numbers. Always answer in English."""

private val QUIZ_SCHEMA = JSONObject(
    """{"type":"OBJECT","properties":{"questions":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
    "question":{"type":"STRING"},"options":{"type":"ARRAY","items":{"type":"STRING"}},"answer":{"type":"INTEGER"},
    "explanation":{"type":"STRING"},"page":{"type":"INTEGER"}},"required":["question","options","answer","explanation"]}}},
    "required":["questions"]}"""
)

private val CARDS_SCHEMA = JSONObject(
    """{"type":"OBJECT","properties":{"cards":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
    "front":{"type":"STRING"},"back":{"type":"STRING"},"page":{"type":"INTEGER"}},"required":["front","back"]}}},
    "required":["cards"]}"""
)

/** Everything behind the "Study assistant" panel. Lives inside the reader's ViewModel. */
class AiAssistant(private val vm: ReaderViewModel) {
    private val app = vm.getApplication<Application>()
    val messages = mutableStateListOf<AiMessage>()
    var busy by mutableStateOf(false); private set
    var scope by mutableStateOf(AiScope.Page)
    var modelName by mutableStateOf(label()); private set

    /** Header text: the model in use, or Auto/Fast while a fresh pick waits for the first question. */
    private fun label() = GeminiSettings.model(app).ifBlank {
        if (GeminiSettings.mode(app) == GeminiSettings.FAST) "Fast (Flash-Lite)" else "Auto (newest Flash)"
    }
    fun refreshModel() { modelName = GeminiSettings.fallback(app) ?: label() }
    private var job: Job? = null
    private val history = mutableListOf<Turn>()
    private var nextId = 1L

    fun hasKey() = GeminiSettings.hasKey(app)

    private enum class Kind { Text, Quiz, Cards }

    // ------------------------------------------------------------------ actions

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val sc = scope
        start(q, null, Kind.Text, q) {
            val (parts, _) = documentParts(range(sc))
            parts + Part.Text("My question: $q")
        }
    }

    fun explainText(text: String, page: Int) {
        val t = text.trim().take(4000)
        if (t.isEmpty()) return
        start("Explain: “${t.take(220)}${if (t.length > 220) "…" else ""}”", null, Kind.Text, "Explain: $t", page) {
            val surrounding = vm.pdf?.pageText(page).orEmpty().take(8000)
            listOf(
                Part.Text(
                    "On page ${page + 1} of my PDF I selected: «$t»\n\n" +
                        "Explain it simply for a student, then list the key exam points. " +
                        "Here is the rest of that page for context:\n$surrounding"
                )
            )
        }
    }

    fun explainRegion(page: Int, rect: RectF) {
        val s = vm.pdf ?: return
        vm.viewModelScope.launch {
            val bmp = s.renderRegion(page, rect)
            val text = s.regionText(page, rect)
            start("Explain this (p. ${page + 1})", bmp?.asImageBitmap(), Kind.Text, "Explain my selection on page ${page + 1}: $text", page) {
                listOfNotNull(
                    Part.Text(
                        "Explain the part of page ${page + 1} I selected. The image shows exactly what I selected " +
                            "(text, a table or a diagram). Text found in the selection, which may be empty for scans or diagrams:\n$text"
                    ),
                    bmp?.let { Part.Image(jpeg(it)) },
                )
            }
        }
    }

    fun summarise(chapter: Boolean) {
        vm.viewModelScope.launch {
            val r = if (chapter) chapterRange(vm.currentPage) else vm.currentPage..vm.currentPage
            val label = label(r)
            start("Summarise $label", null, Kind.Text, "Summarise $label") {
                val (parts, _) = documentParts(r)
                parts + Part.Text(
                    "Summarise this into key exam points: start with a two-sentence overview, then bullet points " +
                        "grouped under short headings, then a \"Must remember\" list of 3-5 high-yield facts. Cite pages."
                )
            }
        }
    }

    fun quiz(source: QuizSource, flashcards: Boolean) {
        vm.viewModelScope.launch {
            val p = vm.currentPage
            val what = when (source) {
                QuizSource.Page -> "page ${p + 1}"
                QuizSource.Chapter -> label(chapterRange(p))
                QuizSource.Highlights -> "my highlights"
            }
            val display = if (flashcards) "Flashcards from $what" else "Quiz me on $what"
            start(display, null, if (flashcards) Kind.Cards else Kind.Quiz, display) {
                val material = when (source) {
                    QuizSource.Page -> documentParts(p..p).first
                    QuizSource.Chapter -> documentParts(chapterRange(p)).first
                    QuizSource.Highlights -> listOf(Part.Text(highlightsText()))
                }
                material + Part.Text(
                    if (flashcards) {
                        "Make 12 flashcards from this material for spaced-repetition revision. Front: a short question or term. " +
                            "Back: a concise answer (max 2 sentences). Include the page number when known. " +
                            "Reply only with JSON: {\"cards\":[{\"front\":\"...\",\"back\":\"...\",\"page\":12}]}"
                    } else {
                        "Write 8 exam-style single-best-answer MCQs with exactly 4 options each, from this material. " +
                            "Mix recall and clinical-application questions. \"answer\" is the 0-based index of the correct option. " +
                            "Give a one or two sentence explanation and the page number when known. " +
                            "Reply only with JSON: {\"questions\":[{\"question\":\"...\",\"options\":[\"...\"],\"answer\":0,\"explanation\":\"...\",\"page\":12}]}"
                    }
                )
            }
        }
    }

    fun stop() { job?.cancel() }

    fun clear() {
        stop()
        messages.clear()
        history.clear()
    }

    fun saveAsNote(m: AiMessage) {
        val text = when {
            m.quiz != null -> m.quiz.mapIndexed { i, q ->
                "${i + 1}. ${q.question}\nAnswer: ${q.options.getOrNull(q.answer) ?: ""}. ${q.explanation}"
            }.joinToString("\n\n")
            m.cards != null -> m.cards.joinToString("\n\n") { "Q: ${it.front}\nA: ${it.back}" }
            else -> plain(m.text)
        }
        vm.addAiNote(m.page, text)
        update(m.id) { it.copy(saved = true) }
    }

    // ------------------------------------------------------------------ engine

    private fun start(display: String, image: ImageBitmap?, kind: Kind, historyText: String, page: Int = vm.currentPage, build: suspend () -> List<Part>) {
        if (busy) return
        if (!hasKey()) { vm.showSettings = true; return }
        messages += AiMessage(nextId++, user = true, text = display, page = page, image = image)
        val replyId = nextId++
        messages += AiMessage(replyId, user = false, text = "", page = page, pending = true)
        busy = true
        job = vm.viewModelScope.launch {
            try {
                val parts = build()
                val turns = history.takeLast(12) + Turn(true, parts)
                when (kind) {
                    Kind.Text -> {
                        var acc = ""
                        withModel { g -> g.stream(SYSTEM, turns).collect { chunk -> acc += chunk; update(replyId) { it.copy(text = acc) } } }
                        if (acc.isBlank()) throw GeminiError("Gemini sent an empty answer. Try again.")
                        update(replyId) { it.copy(pending = false) }
                        remember(historyText, acc)
                    }
                    Kind.Quiz -> {
                        val qs = parseQuiz(withModel { it.generate(SYSTEM, turns, QUIZ_SCHEMA) })
                        if (qs.isEmpty()) throw GeminiError("Couldn't make a quiz from this. Try a page with more text.")
                        update(replyId) { it.copy(text = "${qs.size} questions. Tap an answer to check it.", quiz = qs, pending = false) }
                        remember(historyText, qs.joinToString("\n") { "Q: ${it.question} A: ${it.options.getOrNull(it.answer)}" })
                    }
                    Kind.Cards -> {
                        val cards = parseCards(withModel { it.generate(SYSTEM, turns, CARDS_SCHEMA) })
                        if (cards.isEmpty()) throw GeminiError("Couldn't make flashcards from this. Try a page with more text.")
                        update(replyId) { it.copy(text = "${cards.size} flashcards. Tap a card to flip it.", cards = cards, pending = false) }
                        remember(historyText, cards.joinToString("\n") { "${it.front}: ${it.back}" })
                    }
                }
            } catch (e: CancellationException) {
                update(replyId) { it.copy(pending = false, text = it.text.ifBlank { "Stopped." }) }
                throw e
            } catch (e: GeminiError) {
                update(replyId) { it.copy(pending = false, error = true, text = e.message ?: "Something went wrong.") }
            } catch (e: Exception) {
                update(replyId) { it.copy(pending = false, error = true, text = "Something went wrong: ${e.message}") }
            } finally {
                busy = false
            }
        }
    }


    /**
     * Runs with the saved model (or this session's working backup). If Google says the model is busy
     * (5xx / 429), slow (timeout) or retired (404), it retries once and then moves to a backup model,
     * Flash-Lite first, and keeps using whichever works for the rest of the session.
     */
    private suspend fun <T> withModel(block: suspend (Gemini) -> T): T {
        val key = GeminiSettings.key(app)
        var current = GeminiSettings.fallback(app) ?: Gemini.forSettings(app).model
        val tried = mutableSetOf<String>()
        var last: GeminiError? = null
        repeat(3) {
            modelName = current
            tried += current
            for (wait in listOf(0L, 1500L)) {
                if (wait > 0) kotlinx.coroutines.delay(wait)
                try {
                    val r = block(Gemini(key, current))
                    if (current != GeminiSettings.model(app)) GeminiSettings.rememberFallback(app, current)
                    return r
                } catch (e: GeminiError) {
                    last = e
                    val switchable = e.code >= 500 || e.code == 429 || e.code == 404
                    if (!switchable) throw e
                    if (e.code == 404 || e.code == 504) break // no point retrying the same model
                }
            }
            current = Gemini.pickFallback(Gemini.listModels(key), tried) ?: throw last!!
        }
        throw last ?: GeminiError("Something went wrong.")
    }

    private fun remember(user: String, answer: String) {
        history += Turn(true, listOf(Part.Text(user)))
        history += Turn(false, listOf(Part.Text(answer.take(6000))))
    }

    private fun update(id: Long, f: (AiMessage) -> AiMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    // ------------------------------------------------------------------ context

    private val last get() = (vm.boxes.size - 1).coerceAtLeast(0)

    private suspend fun range(sc: AiScope): IntRange = when (sc) {
        AiScope.Page -> vm.currentPage..vm.currentPage
        AiScope.Chapter -> chapterRange(vm.currentPage)
        AiScope.Document -> 0..last
    }

    /** The chapter around [page] from the PDF's table of contents, or the next few pages if it has none. */
    private suspend fun chapterRange(page: Int): IntRange {
        val outline = vm.outline ?: vm.pdf?.outline().orEmpty().also { vm.cacheOutline(it) }
        val starts = outline.filter { it.page >= 0 && it.level <= 1 }.map { it.page }.distinct().sorted()
        if (starts.isEmpty()) return page..min(page + 4, last)
        val start = starts.lastOrNull { it <= page } ?: 0
        val next = starts.firstOrNull { it > start } ?: (last + 1)
        val end = max(start, next - 1).coerceAtMost(last)
        return start..min(end, start + 59)
    }

    private fun label(r: IntRange) = if (r.first == r.last) "page ${r.first + 1}" else "pages ${r.first + 1}–${r.last + 1}"

    /** Page text with [Page n] markers; scanned pages (no text layer) are sent as images instead. */
    private suspend fun documentParts(r: IntRange, maxChars: Int = 350_000): Pair<List<Part>, String> {
        val s = vm.pdf ?: throw GeminiError("The document isn't open.")
        val sb = StringBuilder()
        val images = mutableListOf<Part>()
        var cut = false
        for (i in r) {
            val t = s.pageText(i)
            if (t.length < 40) {
                if (images.size < 8) {
                    s.render(i, 1100)?.let { bmp ->
                        images += Part.Image(jpeg(bmp))
                        sb.append("[Page ${i + 1}: scanned page, see image ${images.size}]\n\n")
                    }
                }
                continue
            }
            if (sb.length + t.length > maxChars) { cut = true; break }
            sb.append("[Page ${i + 1}]\n").append(t).append("\n\n")
        }
        val title = vm.doc?.title ?: "Document"
        val header = "Content from my PDF \"$title\" (${label(r)})${if (cut) ", cut short because it is very long" else ""}:\n\n"
        return (listOf(Part.Text(header + sb)) + images) to label(r)
    }

    private suspend fun highlightsText(): String {
        val all = vm.pdf?.summary().orEmpty().filter {
            it.contents.isNotBlank() && it.kind in setOf(AnnotKind.Highlight, AnnotKind.Underline, AnnotKind.Note, AnnotKind.Text)
        }
        if (all.isEmpty()) throw GeminiError("There are no highlights or notes in this PDF yet. Highlight some text first.")
        return "My highlights and notes from \"${vm.doc?.title}\":\n" + all.joinToString("\n") { "[Page ${it.page + 1}] ${it.contents}" }
    }

    private suspend fun jpeg(b: Bitmap): ByteArray = withContext(Dispatchers.Default) {
        ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
    }

    // ------------------------------------------------------------------ parsing

    private fun json(raw: String): JSONObject {
        val t = raw.trim()
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) throw GeminiError("Gemini's reply wasn't in the expected format. Try again.")
        return JSONObject(t.substring(a, b + 1))
    }

    private fun parseQuiz(raw: String): List<QuizQ> {
        val arr = json(raw).optJSONArray("questions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val opts = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (opts.size < 2) return@mapNotNull null
            QuizQ(
                question = o.optString("question"),
                options = opts,
                answer = o.optInt("answer").coerceIn(0, opts.size - 1),
                explanation = o.optString("explanation"),
                page = o.optInt("page", 0).takeIf { it > 0 },
            )
        }
    }

    private fun parseCards(raw: String): List<Flashcard> {
        val arr = json(raw).optJSONArray("cards") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Flashcard(o.optString("front"), o.optString("back"), o.optInt("page", 0).takeIf { it > 0 })
        }.filter { it.front.isNotBlank() }
    }

    companion object {
        /** Turns stray LaTeX maths (e.g. "$70 / 120 \\approx 58\\%$") into readable text. */
        fun cleanMath(s: String): String {
            if ('\\' !in s && '$' !in s) return s
            var t = s
            repeat(3) { t = t.replace(Regex("\\\\(?:text|mathrm|mathbf|textbf|operatorname)\\{([^{}]*)\\}"), "$1") }
            t = t.replace(Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}"), "($1)/($2)")
            val symbols = mapOf(
                "\\approx" to "≈", "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±",
                "\\leq" to "≤", "\\geq" to "≥", "\\le" to "≤", "\\ge" to "≥", "\\neq" to "≠",
                "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←", "\\uparrow" to "↑", "\\downarrow" to "↓",
                "\\Delta" to "Δ", "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\mu" to "µ",
                "\\%" to "%", "^\\circ" to "°", "\\circ" to "°", "\\," to " ", "\\;" to " ", "\\ " to " ",
            )
            symbols.forEach { (k, v) -> t = t.replace(k, v) }
            t = t.replace(Regex("\\$\\$?([^$\\n]+?)\\$\\$?"), "$1")   // drop $...$ delimiters
            t = t.replace(Regex("\\^\\{([^{}]*)\\}"), "^$1").replace(Regex("_\\{([^{}]*)\\}"), "$1")
            return t.replace("{", "").replace("}", "")
        }

        /** Markdown -> plain text for saving into a PDF note. */
        fun plain(md: String) = cleanMath(md).lines().joinToString("\n") { line ->
            line.replace(Regex("^#+\\s*"), "")
                .replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
                .replace("**", "").replace("__", "").replace("`", "")
        }.trim()
    }
}
