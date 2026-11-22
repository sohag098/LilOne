package app.lilone

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.lilone.ui.theme.AppTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lilone.data.Importer
import app.lilone.data.License
import app.lilone.ui.ActivationScreen
import app.lilone.data.Sharing
import app.lilone.ui.library.ImportMimeTypes
import app.lilone.ui.library.LibraryScreen
import app.lilone.ui.reader.ReaderScreen
import app.lilone.ui.theme.LilOneTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Files handed to us by another app. [fromApp] is that app's name (e.g. "Files", "WhatsApp") when Android tells us. */
data class Incoming(val uris: List<Uri>, val fromApp: String?)

class MainActivity : ComponentActivity() {
    /** Files handed to us by other apps ("Open with" / "Share"). */
    private val incoming = MutableSharedFlow<Incoming>(replay = 1, extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.load(this)
        License.load(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val dark = AppTheme.isDark()
            // keep status/navigation bar icons readable for the chosen Light/Dark mode
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LilOneTheme(dark) {
                LaunchedEffect(Unit) { License.recheck(this@MainActivity) }
                Surface(color = MaterialTheme.colorScheme.background) {
                    // Files opened from other apps wait (replayed) until the app is activated
                    if (License.unlocked) App(incoming) else ActivationScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            else -> emptyList()
        }
        if (uris.isNotEmpty()) incoming.tryEmit(Incoming(uris, callerName() ?: ownerName(uris.first())))
    }

    /** Name of the app that opened us, so the reader can offer "‹ Files" to go straight back there. */
    private fun callerName(): String? = runCatching {
        val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host
        if (pkg == null || pkg == packageName) return null
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Most file managers don't say who they are, so name the app that owns the file link instead:
     * WhatsApp, Gmail, Files by Google... Phone storage and Downloads just show "Files".
     */
    private fun ownerName(uri: Uri): String? = runCatching {
        val authority = uri.authority ?: return null
        if (uri.scheme == "file" || authority.startsWith("com.android.externalstorage") ||
            authority.startsWith("com.android.providers.downloads") || authority.startsWith("com.android.providers.media") ||
            authority == "media"
        ) return "Files"
        val info = packageManager.resolveContentProvider(authority, 0)?.applicationInfo ?: return null
        if (info.packageName == packageName) return null
        packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
    }.getOrNull()
}

@Composable
private fun App(incoming: MutableSharedFlow<Incoming>) {
    val context = LocalContext.current
    // Keeps the library's chosen folder, search and scroll while a file is open
    val saveableState = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // Where the reader's back button goes: a library folder, or the app the file came from
    var backLabel by rememberSaveable { mutableStateOf("Library") }
    var fromOtherApp by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importSubject by remember { mutableStateOf<String?>(null) }
    var needsConversion by remember { mutableStateOf<List<Uri>>(emptyList()) }

    fun runImport(uris: List<Uri>, subjectId: String?, fromApp: String? = null, external: Boolean = false) {
        if (uris.isEmpty()) return
        scope.launch {
            importing = true
            val r = Importer.import(context, uris, subjectId)
            importing = false
            needsConversion = r.needsConversion
            val single = (r.imported + r.alreadyHad).singleOrNull()?.takeIf { uris.size == 1 }
            when {
                single != null -> {
                    fromOtherApp = external
                    backLabel = if (external) fromApp ?: "Back" else "Library"
                    openId = single
                    if (r.alreadyHad.isNotEmpty()) launch { snackbar.showSnackbar("Already in your library. Opened your copy with its notes.") }
                }
                r.imported.isNotEmpty() || r.alreadyHad.isNotEmpty() -> snackbar.showSnackbar(
                    listOfNotNull(
                        r.imported.size.takeIf { it > 0 }?.let { "Added $it file${if (it > 1) "s" else ""}" },
                        r.alreadyHad.size.takeIf { it > 0 }?.let { "$it already in your library" },
                    ).joinToString(" · ")
                )
            }
            if (r.failed.isNotEmpty()) snackbar.showSnackbar("Couldn't read: ${r.failed.joinToString()}")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        runImport(uris, importSubject)
    }

    LaunchedEffect(Unit) {
        incoming.collect { inc ->
            incoming.resetReplayCache()
            openId = null
            runImport(inc.uris, null, inc.fromApp, external = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        val id = openId
        if (id == null) {
            saveableState.SaveableStateProvider("library") {
                LibraryScreen(
                    importing = importing,
                    onImport = { subject -> importSubject = subject; picker.launch(ImportMimeTypes) },
                    onOpen = { docId, from -> fromOtherApp = false; backLabel = from; openId = docId },
                )
            }
        } else {
            ReaderScreen(
                id,
                backLabel = backLabel,
                fromOtherApp = fromOtherApp,
                onBack = {
                    openId = null
                    if (fromOtherApp) {
                        // Back to the file manager / chat she opened it from. Lil One waits on its library.
                        fromOtherApp = false
                        (context as? android.app.Activity)?.moveTaskToBack(true)
                    }
                },
                onLibrary = { fromOtherApp = false; openId = null },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }

    if (needsConversion.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { needsConversion = emptyList() },
            title = { Text("This file needs one extra step") },
            text = {
                Column {
                    Text("Lil One couldn't convert this file by itself. To study it here:")
                    Spacer(Modifier.height(10.dp))
                    Text("1. Open it in Google Docs, Slides or Word.")
                    Text("2. Tap ⋮ → Share & export → Print (or Save as) → PDF.")
                    Text("3. Open the PDF with Lil One.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Sharing.openElsewhere(context, needsConversion.first())
                    needsConversion = emptyList()
                }) { Text("Open in another app") }
            },
            dismissButton = { TextButton(onClick = { needsConversion = emptyList() }) { Text("OK") } },
        )
    }
}
