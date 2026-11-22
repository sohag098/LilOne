package app.lilone.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GeminiError(message: String, val code: Int = 0) : Exception(message)

/** The user's own Gemini API key + chosen model, stored privately on the device. */
object GeminiSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun key(c: Context): String = prefs(c).getString("gemini_key", "")!!.trim()
    /** The model in use right now (for Auto/Fast it's the one last picked automatically). */
    fun model(c: Context): String = prefs(c).getString("gemini_model", "")!!
    fun hasKey(c: Context) = key(c).isNotBlank()

    /** Auto = newest Flash, Fast = newest Flash-Lite (both re-picked weekly), Custom = the exact model the user chose. */
    const val AUTO = "auto"
    const val FAST = "fast"
    const val CUSTOM = "custom"
    fun mode(c: Context): String = prefs(c).getString("gemini_mode", null)
        ?: if ("flash-lite" in model(c)) FAST else AUTO // older versions: keep the earlier choice
    fun pickedAt(c: Context): Long = prefs(c).getLong("gemini_model_at", 0)

    fun saveModel(c: Context, model: String) =
        prefs(c).edit().putString("gemini_model", model).putLong("gemini_model_at", System.currentTimeMillis()).apply()

    /** [picked] = the Auto/Fast model already worked out from this key's model list, if we have it. */
    fun saveSettings(c: Context, key: String, mode: String, customModel: String, picked: String? = null) {
        val m = if (mode == CUSTOM && customModel.isBlank()) AUTO else mode
        val changed = m != mode(c) || key.trim() != key(c)
        val e = prefs(c).edit().putString("gemini_key", key.trim()).putString("gemini_mode", m)
        when {
            m == CUSTOM -> e.putString("gemini_model", customModel).putLong("gemini_model_at", System.currentTimeMillis())
            picked != null -> e.putString("gemini_model", picked).putLong("gemini_model_at", System.currentTimeMillis())
            changed -> e.putString("gemini_model", "") // pick again on next use
        }
        e.apply()
    }

    /** A backup model that worked while the chosen one was overloaded; remembered for an hour. */
    fun fallback(c: Context): String? {
        val p = prefs(c)
        val until = p.getLong("gemini_fallback_until", 0)
        val m = p.getString("gemini_fallback", null)
        return if (m != null && System.currentTimeMillis() < until && p.getString("gemini_fallback_for", null) == model(c)) m else null
    }
    fun rememberFallback(c: Context, m: String) = prefs(c).edit()
        .putString("gemini_fallback", m).putString("gemini_fallback_for", model(c))
        .putLong("gemini_fallback_until", System.currentTimeMillis() + 60 * 60 * 1000L).apply()
}

sealed interface Part {
    data class Text(val text: String) : Part
    class Image(val jpeg: ByteArray) : Part
}

data class Turn(val user: Boolean, val parts: List<Part>)

/** Minimal REST client for the Gemini API (generateContent / streamGenerateContent). */
class Gemini(private val apiKey: String, val model: String) {

    /** Streams the answer as text chunks. */
    fun stream(system: String, turns: List<Turn>): Flow<String> = flow {
        val c = open("POST", "$BASE/models/$model:streamGenerateContent?alt=sse", apiKey)
        val t0 = System.currentTimeMillis()
        try {
            send(c, body(system, turns, null))
            log("stream $model: sent, waiting")
            check(c)
            log("stream $model: HTTP ${c.responseCode} after ${System.currentTimeMillis() - t0} ms")
            val reader = c.inputStream.bufferedReader()
            var first = true
            while (true) {
                val line = reader.readLine() ?: break
                if (first) { log("stream $model: first line after ${System.currentTimeMillis() - t0} ms: ${line.take(120)}"); first = false }
                if (!line.startsWith("data:")) continue
                val json = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                blocked(json)
                val t = textOf(json)
                if (t.isNotEmpty()) emit(t)
            }
        } catch (e: java.net.SocketTimeoutException) {
            log("stream $model: timeout after ${System.currentTimeMillis() - t0} ms")
            throw GeminiError("Gemini took too long to answer. Try again.", 504)
        } catch (e: IOException) {
            log("stream $model: ${e.javaClass.simpleName} ${e.message}")
            throw GeminiError("No internet connection. Check Wi-Fi and try again.")
        } finally {
            c.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** One-shot answer. With [schema], asks for JSON matching it (falls back to prompt-only JSON). */
    suspend fun generate(system: String, turns: List<Turn>, schema: JSONObject? = null): String = withContext(Dispatchers.IO) {
        try {
            post(system, turns, schema)
        } catch (e: GeminiError) {
            if (schema != null && e.code == 400 && !e.message.orEmpty().contains("key", true)) post(system, turns, null) else throw e
        }
    }

    private fun post(system: String, turns: List<Turn>, schema: JSONObject?): String {
        val c = open("POST", "$BASE/models/$model:generateContent", apiKey)
        try {
            send(c, body(system, turns, schema))
            check(c)
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            blocked(json)
            return textOf(json)
        } catch (e: IOException) {
            throw GeminiError("No internet connection. Check Wi-Fi and try again.")
        } finally {
            c.disconnect()
        }
    }

    private fun body(system: String, turns: List<Turn>, schema: JSONObject?): String {
        val o = JSONObject()
        o.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        o.put("contents", JSONArray().apply {
            turns.forEach { t ->
                put(JSONObject().put("role", if (t.user) "user" else "model").put("parts", JSONArray().apply {
                    t.parts.forEach { p ->
                        when (p) {
                            is Part.Text -> put(JSONObject().put("text", p.text))
                            is Part.Image -> put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(p.jpeg, Base64.NO_WRAP))
                                )
                            )
                        }
                    }
                }))
            }
        })
        if (schema != null) {
            o.put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema))
        }
        return o.toString()
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val KEY_PAGE = "https://aistudio.google.com/apikey"

        /** Models this key can use for text generation, e.g. "gemini-3.8-flash". */
        suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
            val out = mutableListOf<String>()
            var token: String? = null
            try {
                do {
                    val c = open("GET", "$BASE/models?pageSize=200" + (token?.let { "&pageToken=$it" } ?: ""), key)
                    try {
                        check(c)
                        val json = JSONObject(c.inputStream.bufferedReader().readText())
                        val arr = json.optJSONArray("models") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val m = arr.getJSONObject(i)
                            val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                            val canGenerate = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                            if (canGenerate) out += m.optString("name").removePrefix("models/")
                        }
                        token = json.optString("nextPageToken").ifEmpty { null }
                    } finally {
                        c.disconnect()
                    }
                } while (token != null)
            } catch (e: IOException) {
                throw GeminiError("No internet connection. Check Wi-Fi and try again.")
            }
            out
        }

        /** Newest stable "Flash" text model: fast, cheap and good for studying. */
        fun pickBest(models: List<String>, exclude: Set<String> = emptySet()): String? = pickBestOf(models.filterNot { it in exclude })

        /** Backup when the main model is overloaded: the newest Flash-Lite first (fast, rarely busy), then other Flash models. */
        fun pickFallback(models: List<String>, exclude: Set<String>): String? {
            val pool = models.filterNot { it in exclude }
            return pickLite(pool) ?: pickBestOf(pool)
        }

        /** Newest stable Flash-Lite: the quick option. */
        fun pickLite(models: List<String>): String? {
            val lite = models.filter { it.startsWith("gemini") && "flash-lite" in it && listOf("image", "tts", "live", "audio").none { s -> s in it } }
            val stableLite = lite.filter { "preview" !in it }.ifEmpty { lite }
            return stableLite.maxWithOrNull(compareBy({ ver(it) }, { -it.length }))
        }

        fun pickFor(mode: String, models: List<String>): String? =
            if (mode == GeminiSettings.FAST) pickLite(models) ?: pickBest(models) else pickBest(models)

        private val notChat = listOf("image", "tts", "live", "audio", "embed", "robotics", "computer", "gemma", "native", "learnlm", "aqa", "veo", "imagen", "thinking", "transcribe", "customtools")

        /** Flash / Flash-Lite / Pro models that can chat about the PDF (no codenames, image or voice models), newest first. */
        fun chatModels(models: List<String>): List<String> =
            models.filter { m -> m.startsWith("gemini") && notChat.none { m.contains(it) } && ("flash" in m || "pro" in m) }
                .sortedWith(compareByDescending<String> { ver(it) }.thenBy { it })

        private fun ver(m: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(m)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

        private fun pickBestOf(models: List<String>): String? {
            val text = models.filter { m -> m.startsWith("gemini") && notChat.none { m.contains(it) } }
            val flash = text.filter { "flash" in it && "lite" !in it }
            val stable = flash.filter { "preview" !in it && "exp" !in it }
            val pool = stable.ifEmpty { flash }.ifEmpty { text }
            return pool.maxWithOrNull(compareBy<String>({ ver(it) }, { if (Regex("-\\d{3}$").containsMatchIn(it)) 0 else 1 }, { -it.length }))
        }

        /**
         * The model for the chosen setting. Auto/Fast are re-picked once a week so newer models are picked up automatically;
         * if that check fails (offline), the last pick keeps working.
         */
        suspend fun forSettings(context: Context, refresh: Boolean = false): Gemini {
            val key = GeminiSettings.key(context)
            if (key.isBlank()) throw GeminiError("Add your Gemini API key in Settings first.")
            val mode = GeminiSettings.mode(context)
            var model = GeminiSettings.model(context)
            val stale = mode != GeminiSettings.CUSTOM && System.currentTimeMillis() - GeminiSettings.pickedAt(context) > WEEK
            if (model.isBlank() || refresh || stale) {
                val picked = try {
                    pickFor(mode, listModels(key))
                } catch (e: GeminiError) {
                    if (model.isBlank()) throw e else null
                }
                if (picked != null) { model = picked; GeminiSettings.saveModel(context, model) }
                else if (model.isBlank()) throw GeminiError("This key has no text models available.")
            }
            return Gemini(key, model)
        }

        private const val WEEK = 7L * 24 * 60 * 60 * 1000

        private fun open(method: String, url: String, key: String): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 25_000
                setRequestProperty("x-goog-api-key", key)
            }

        private fun send(c: HttpURLConnection, body: String) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
        }

        private fun check(c: HttpURLConnection) {
            val code = c.responseCode
            if (code in 200..299) return
            val raw = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
            log("HTTP $code: ${raw.take(300)}")
            val msg = runCatching { JSONObject(raw).getJSONObject("error").optString("message") }.getOrDefault("")
            throw GeminiError(
                when {
                    msg.contains("API key", true) && (code == 400 || code == 401 || code == 403) ->
                        "That Gemini API key isn't valid. Make sure you copied the whole key from AI Studio."
                    msg.contains("location", true) || msg.contains("region", true) ->
                        "Gemini isn't available in this region for this key."
                    code == 429 -> "Gemini's free limit is used up for the moment. Wait a minute and try again."
                    code == 404 -> "That AI model isn't available any more. Open Settings and tap Check key."
                    code >= 500 -> "Gemini is busy right now ($code${if (msg.isNotBlank()) ": " + msg.take(140) else ""}). Try again in a moment."
                    else -> "Gemini error ($code): ${msg.take(180)}"
                },
                code,
            )
        }

        /** Diagnostics only: status codes and timings. Never logs the key or the document text. */
        private fun log(msg: String) { android.util.Log.i("LilOneAI", msg) }

        private fun blocked(json: JSONObject) {
            val reason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            if (reason.isNotEmpty()) throw GeminiError("Gemini declined to answer this one ($reason).")
        }

        private fun textOf(json: JSONObject): String {
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return ""
            return buildString {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.optBoolean("thought")) continue
                    append(p.optString("text"))
                }
            }
        }
    }
}
