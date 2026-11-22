package app.lilone.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Subject(val id: String, val name: String, val color: Long)

data class Doc(
    val id: String,
    val title: String,
    val subjectId: String?,
    val added: Long,
    val lastOpened: Long = 0,
    val lastPage: Int = 0,
    val pageCount: Int = 0,
    val bookmarks: List<Int> = emptyList(),
    /** SHA-256 of the file as it was imported, so opening the same file again reuses this copy. */
    val sourceHash: String? = null,
)

data class LibraryData(val subjects: List<Subject> = emptyList(), val docs: List<Doc> = emptyList())

/** Soft colours used to tag subjects. */
val SubjectColors = listOf(
    0xFFE88FA5, 0xFF8BC6A8, 0xFF8DB4E8, 0xFFF2B872, 0xFFB79BE0, 0xFFE8A07E, 0xFF7FC4C9, 0xFFD6A2C8,
)

/**
 * The library lives in the app's private storage:
 *   files/library/index.json   subjects + document metadata
 *   files/library/<id>.pdf     the documents themselves (annotations are saved into these)
 *   files/library/thumbs/<id>.jpg
 */
class LibraryRepo private constructor(context: Context) {
    val root = File(context.filesDir, "library").apply { mkdirs() }
    private val thumbs = File(root, "thumbs").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val lock = Any()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<LibraryData> = _state.asStateFlow()

    fun file(id: String) = File(root, "$id.pdf")
    fun thumb(id: String) = File(thumbs, "$id.jpg")
    fun doc(id: String) = _state.value.docs.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---- documents ----
    fun addDoc(doc: Doc) = mutate { it.copy(docs = it.docs + doc) }

    fun updateDoc(id: String, f: (Doc) -> Doc) =
        mutate { d -> d.copy(docs = d.docs.map { if (it.id == id) f(it) else it }) }

    fun renameDoc(id: String, title: String) = updateDoc(id) { it.copy(title = title.trim().ifEmpty { it.title }) }
    fun moveDoc(id: String, subjectId: String?) = updateDoc(id) { it.copy(subjectId = subjectId) }

    fun deleteDoc(id: String) {
        file(id).delete(); thumb(id).delete()
        mutate { d -> d.copy(docs = d.docs.filterNot { it.id == id }) }
    }

    fun markOpened(id: String) = updateDoc(id) { it.copy(lastOpened = System.currentTimeMillis()) }
    fun setProgress(id: String, page: Int, count: Int) =
        updateDoc(id) { it.copy(lastPage = page, pageCount = count) }

    fun toggleBookmark(id: String, page: Int) = updateDoc(id) {
        val b = if (page in it.bookmarks) it.bookmarks - page else (it.bookmarks + page).sorted()
        it.copy(bookmarks = b)
    }

    // ---- subjects ----
    fun addSubject(name: String): Subject {
        val used = _state.value.subjects.size
        val s = Subject(newId(), name.trim(), SubjectColors[used % SubjectColors.size])
        mutate { it.copy(subjects = it.subjects + s) }
        return s
    }

    fun renameSubject(id: String, name: String) =
        mutate { d -> d.copy(subjects = d.subjects.map { if (it.id == id) it.copy(name = name.trim()) else it }) }

    /** Deleting a subject keeps its files; they go back to "Unsorted". */
    fun deleteSubject(id: String) = mutate { d ->
        d.copy(
            subjects = d.subjects.filterNot { it.id == id },
            docs = d.docs.map { if (it.subjectId == id) it.copy(subjectId = null) else it },
        )
    }

    // ---- persistence ----
    private fun mutate(f: (LibraryData) -> LibraryData) {
        synchronized(lock) {
            val next = f(_state.value)
            _state.value = next
            save(next)
        }
    }

    private fun save(d: LibraryData) {
        val json = JSONObject()
        json.put("subjects", JSONArray().apply {
            d.subjects.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        })
        json.put("docs", JSONArray().apply {
            d.docs.forEach {
                put(
                    JSONObject()
                        .put("id", it.id).put("title", it.title).put("subject", it.subjectId ?: JSONObject.NULL)
                        .put("added", it.added).put("opened", it.lastOpened).put("page", it.lastPage)
                        .put("pages", it.pageCount).put("bookmarks", JSONArray(it.bookmarks))
                        .put("src", it.sourceHash ?: JSONObject.NULL)
                )
            }
        })
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(indexFile)
    }

    private fun load(): LibraryData {
        if (!indexFile.exists()) return LibraryData()
        return try {
            val json = JSONObject(indexFile.readText())
            val subjects = json.optJSONArray("subjects").objects().map {
                Subject(it.getString("id"), it.getString("name"), it.optLong("color", SubjectColors[0]))
            }
            val docs = json.optJSONArray("docs").objects().map { o ->
                Doc(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    subjectId = if (o.isNull("subject")) null else o.optString("subject"),
                    added = o.optLong("added"),
                    lastOpened = o.optLong("opened"),
                    lastPage = o.optInt("page"),
                    pageCount = o.optInt("pages"),
                    bookmarks = o.optJSONArray("bookmarks")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList(),
                    sourceHash = if (o.isNull("src")) null else o.optString("src").ifEmpty { null },
                )
            }.filter { File(root, "${it.id}.pdf").exists() }
            LibraryData(subjects, docs)
        } catch (e: Exception) {
            LibraryData()
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        @Volatile private var instance: LibraryRepo? = null
        fun get(context: Context): LibraryRepo =
            instance ?: synchronized(this) {
                instance ?: LibraryRepo(context.applicationContext).also { instance = it }
            }
    }
}
