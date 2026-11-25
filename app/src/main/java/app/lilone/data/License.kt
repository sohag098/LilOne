package app.lilone.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * License keys sold through SupportKori (https://www.supportkori.com/help/article/setup-license-keys).
 *
 * - Activating sends the key with this install's id and uses one of the key's seats (2 devices).
 * - Every app start re-checks quietly (no seat used). A refunded or disabled key locks the app again.
 * - Offline, the last good check keeps the app unlocked for [OFFLINE_GRACE_MS].
 * - Before buying, a free trial unlocks everything for [TRIAL_MS] from the first launch (see [trialEndsAt]).
 *
 * Everything is stored in noBackupFilesDir, so a backup restored onto another phone does not carry the
 * activation or the install id with it (that phone has to activate, using its own seat).
 * The product token is public by design (like a Gumroad product id); the buyer's key is the private part.
 */
object License {
    /** Personal edition for family and friends (built with -Ppersonal=true): no key needed. */
    val personal: Boolean get() = app.lilone.BuildConfig.PERSONAL_EDITION

    const val PRODUCT_TOKEN = "-bdlB_4dKxg63KxM2jEeZqEz"
    const val SHOP_URL = "https://masudsohag32.supportkori.shop"
    const val SUPPORT_EMAIL = "masudsohag32@gmail.com"
    private const val VERIFY_URL = "https://supportkori.com/api/licenses/verify"
    private const val DEACTIVATE_URL = "https://supportkori.com/api/licenses/deactivate"
    private const val RECHECK_MS = 6L * 60 * 60 * 1000 // re-check at most every 6 hours
    private const val OFFLINE_GRACE_MS = 14L * 24 * 60 * 60 * 1000 // works 14 days without internet
    const val TRIAL_MS = 24L * 60 * 60 * 1000 // 1-day free trial
    private const val CLOCK_SLACK_MS = 60L * 60 * 1000 // the clock moving back more than this ends the trial

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
        data object Offline : Result
    }

    /** True while the app may be used (activated key, or a running trial). Compose reads it, so the activation screen appears or goes away by itself. */
    var unlocked by mutableStateOf(false)
        private set

    /** True when unlocked by a key (or the personal edition), not just by the trial. */
    var activated by mutableStateOf(false)
        private set

    /** When the free trial ends, while it is running and no key is active; null otherwise. */
    var trialEndsAt by mutableStateOf<Long?>(null)
        private set

    /** True once the trial has run out (the activation screen then says so). */
    var trialOver by mutableStateOf(false)
        private set

    /** Shows the key screen during the trial ("I have a key"), with a way back. */
    var showActivation by mutableStateOf(false)

    /** Shown on the activation screen after the app locked itself (refund, disabled key, too long offline). */
    var lockMessage by mutableStateOf<String?>(null)

    private data class State(val key: String, val instanceId: String, val lastOk: Long, val lastCheck: Long)

    private fun dir(c: Context) = c.applicationContext.noBackupFilesDir
    private fun stateFile(c: Context) = File(dir(c), "license.json")
    private fun trialFile(c: Context) = File(dir(c), "trial.json")

    /**
     * Trial bookkeeping: when it started, the latest time the app saw (so turning the clock back doesn't add time),
     * and whether it was ended early (a refunded or disabled key). Reinstalling starts a new trial; that's accepted.
     */
    private data class Trial(val start: Long, val lastSeen: Long, val ended: Boolean)

    private fun readTrial(c: Context): Trial? = runCatching {
        val o = JSONObject(trialFile(c).readText())
        Trial(o.getLong("start"), o.getLong("lastSeen"), o.optBoolean("ended", false))
    }.getOrNull()

    private fun writeTrial(c: Context, t: Trial) {
        trialFile(c).writeText(JSONObject().put("start", t.start).put("lastSeen", t.lastSeen).put("ended", t.ended).toString())
    }

    /** Starts the trial on the very first launch and returns it, updated to now. */
    private fun trial(c: Context, now: Long): Trial {
        val old = readTrial(c) ?: Trial(now, now, false).also { writeTrial(c, it) }
        val wentBack = now < old.lastSeen - CLOCK_SLACK_MS || now < old.start - CLOCK_SLACK_MS
        val t = old.copy(lastSeen = maxOf(old.lastSeen, now), ended = old.ended || wentBack)
        if (t != old) writeTrial(c, t)
        return t
    }

    private fun endTrial(c: Context) {
        val now = System.currentTimeMillis()
        val t = readTrial(c) ?: Trial(now, now, true)
        writeTrial(c, t.copy(ended = true))
    }

    /**
     * Works out [unlocked], [activated] and the trial from the saved state (no network).
     * Called at start, on resume and once a minute, so the trial ends on time while the app is open.
     */
    fun refresh(c: Context) {
        if (personal) { unlocked = true; activated = true; trialEndsAt = null; trialOver = false; return }
        val now = System.currentTimeMillis()
        val t = trial(c, now)
        val s = read(c)
        activated = s != null && now - s.lastOk < OFFLINE_GRACE_MS
        val ends = t.start + TRIAL_MS
        val trialRunning = !t.ended && now < ends
        trialEndsAt = if (!activated && trialRunning) ends else null
        trialOver = !activated && !trialRunning
        unlocked = activated || trialRunning
        if (activated) showActivation = false
    }

    /** "23 h left", "45 min left". */
    fun trialLeftText(now: Long = System.currentTimeMillis()): String? {
        val left = (trialEndsAt ?: return null) - now
        val min = (left + 59_999) / 60_000
        return if (min >= 60) "${(min + 30) / 60} h left" else "${min.coerceAtLeast(1)} min left"
    }

    /** A random id for this install that stays the same across restarts (SupportKori's "instance_id"). */
    fun instanceId(c: Context): String {
        val f = File(dir(c), "instance_id")
        f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return UUID.randomUUID().toString().also { f.writeText(it) }
    }

    private fun read(c: Context): State? = runCatching {
        val o = JSONObject(stateFile(c).readText())
        State(o.getString("key"), o.getString("instance"), o.getLong("lastOk"), o.getLong("lastCheck"))
    }.getOrNull()?.takeIf { it.instanceId == instanceId(c) }

    private fun write(c: Context, s: State?) {
        if (s == null) { stateFile(c).delete(); return }
        stateFile(c).writeText(
            JSONObject().put("key", s.key).put("instance", s.instanceId).put("lastOk", s.lastOk).put("lastCheck", s.lastCheck).toString()
        )
    }

    /** The saved key, for showing in Settings (masked). */
    fun key(c: Context): String? = read(c)?.key

    fun masked(key: String) = key.split('-').let { parts ->
        if (parts.size > 1) parts.dropLast(1).joinToString("-") { "•".repeat(it.length) } + "-" + parts.last() else "••••" + key.takeLast(4)
    }

    /** Call before the first frame: unlocks right away from the saved state (no network). */
    fun load(c: Context) = refresh(c)

    /** Call at startup after [load]: re-checks the key in the background when due. */
    suspend fun recheck(c: Context) {
        if (personal) return
        val s = read(c) ?: return
        val now = System.currentTimeMillis()
        if (now - s.lastCheck < RECHECK_MS && activated) return
        when (val r = verify(s.key, c, activate = false)) {
            Result.Valid -> { write(c, s.copy(lastOk = now, lastCheck = now)); refresh(c) }
            is Result.Invalid -> lock(c, message(r.reason))
            Result.Offline -> {
                write(c, s.copy(lastCheck = now))
                if (!activated) lockMessage = "Connect to the internet once so Lil One can check your license."
            }
        }
    }

    /** Activate with a key the buyer typed. Uses one seat (re-activating this same device is free). */
    suspend fun activate(c: Context, typed: String): Result {
        val key = normalise(typed)
        val r = verify(key, c, activate = true)
        if (r == Result.Valid) {
            val now = System.currentTimeMillis()
            write(c, State(key, instanceId(c), now, now))
            lockMessage = null
            refresh(c)
        }
        return r
    }

    /** "Remove this device": frees the seat on SupportKori and locks the app here. Needs internet. */
    suspend fun removeDevice(c: Context): Boolean {
        val s = read(c) ?: run { refresh(c); return true }
        val ok = post(DEACTIVATE_URL, JSONObject().put("product_id", PRODUCT_TOKEN).put("license_key", s.key).put("instance_id", s.instanceId)) != null
        if (ok) { write(c, null); lockMessage = null; refresh(c) }
        return ok
    }

    /** A refunded or disabled key also ends any trial that is still running. */
    private fun lock(c: Context, msg: String) {
        write(c, null)
        endTrial(c)
        lockMessage = msg
        refresh(c)
    }

    fun normalise(typed: String) = typed.trim().uppercase().replace(Regex("\\s+"), "")

    /** Friendly text for SupportKori's reasons. */
    fun message(reason: String): String = when (reason) {
        "not_found" -> "That key wasn't found. Check it for typos. It's in your purchase email and your SupportKori library."
        "product_mismatch" -> "That key is for a different product."
        "seat_limit_reached" -> "This key is already active on 2 devices. On the old device open Settings → Remove this device, then try again."
        "refunded" -> "This purchase was refunded, so the key no longer works."
        "disabled" -> "This key has been disabled. Please contact $SUPPORT_EMAIL."
        "offline" -> "Couldn't reach the license server. Check your internet and try again."
        else -> "Couldn't check the key ($reason). Please try again."
    }

    private suspend fun verify(key: String, c: Context, activate: Boolean): Result {
        val body = JSONObject().put("product_id", PRODUCT_TOKEN).put("license_key", key)
        if (activate) body.put("increment_uses_count", true).put("instance_id", instanceId(c))
        val reply = post(VERIFY_URL, body) ?: return Result.Offline
        return if (reply.optBoolean("valid", false)) Result.Valid
        else Result.Invalid(reply.optString("reason").ifBlank { "unknown" })
    }

    /** POSTs JSON and returns the JSON reply, or null when the server can't be reached. */
    private suspend fun post(url: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                if (code >= 500 || text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            null
        }
    }
}
