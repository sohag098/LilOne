package app.lilone.ui.library

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.ui.res.painterResource
import app.lilone.R
import app.lilone.ui.theme.AppTheme
import app.lilone.ui.theme.Blush
import app.lilone.ui.theme.Cream
import app.lilone.ui.theme.ThemeMode
import app.lilone.ui.theme.WineDeep
import app.lilone.ui.theme.WineGradient
import app.lilone.data.Doc
import app.lilone.data.LibraryRepo
import app.lilone.data.Sharing
import app.lilone.data.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

private const val ALL = "all"
private const val RECENT = "recent"
private const val UNSORTED = "unsorted"

val ImportMimeTypes = arrayOf(
    "application/pdf",
    "image/*",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/epub+zip",
)

@Composable
fun LibraryScreen(
    importing: Boolean,
    onImport: (subjectId: String?) -> Unit,
    /** [from] is the folder that was open, so the reader's back button can name it. */
    onOpen: (id: String, from: String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { LibraryRepo.get(context) }
    val data by repo.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var newSubject by remember { mutableStateOf(false) }
    var renameDoc by remember { mutableStateOf<Doc?>(null) }
    var moveDoc by remember { mutableStateOf<Doc?>(null) }
    var deleteDoc by remember { mutableStateOf<Doc?>(null) }
    var subjectMenu by remember { mutableStateOf<Subject?>(null) }
    var renameSubject by remember { mutableStateOf<Subject?>(null) }
    var pendingCopy by remember { mutableStateOf<Doc?>(null) }
    var settings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val d = pendingCopy
        if (uri != null && d != null) scope.launch(Dispatchers.IO) { Sharing.writeTo(context, repo.file(d.id), uri) }
        pendingCopy = null
    }

    if (filter !in listOf(ALL, RECENT, UNSORTED) && data.subjects.none { it.id == filter }) filter = ALL
    val currentSubject = data.subjects.firstOrNull { it.id == filter }
    val docs = data.docs
        .filter {
            when (filter) {
                ALL -> true
                RECENT -> it.lastOpened > 0
                UNSORTED -> it.subjectId == null
                else -> it.subjectId == filter
            }
        }
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { if (filter == RECENT) it.lastOpened else maxOf(it.lastOpened, it.added) }
        .let { if (filter == RECENT) it.take(30) else it }
    val continueDoc = data.docs.filter { it.lastOpened > 0 }.maxByOrNull { it.lastOpened }
        ?.takeIf { filter == ALL && query.isBlank() }

    val backLabel = when (filter) {
        ALL -> "Library"
        RECENT -> "Recent"
        UNSORTED -> "Unsorted"
        else -> currentSubject?.name ?: "Library"
    }
    val actions = DocActions(
        open = { onOpen(it, backLabel) },
        rename = { renameDoc = it },
        move = { moveDoc = it },
        share = { d -> Sharing.sharePdf(context, Sharing.shareableCopy(context, repo.file(d.id), d.title)) },
        saveCopy = { d -> pendingCopy = d; saveCopy.launch(Sharing.safeName(d.title) + ".pdf") },
        delete = { deleteDoc = it },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                SubjectRail(
                    data.subjects, data.docs, filter,
                    onSelect = { filter = it },
                    onNew = { newSubject = true },
                    onMenu = { subjectMenu = it },
                    onSettings = { settings = true },
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) {
                val count = docs.size
                Header(
                    title = when (filter) {
                        ALL -> greeting()
                        RECENT -> "Recently opened"
                        UNSORTED -> "Unsorted"
                        else -> currentSubject?.name ?: ""
                    },
                    subtitle = when (filter) {
                        ALL -> if (data.docs.isEmpty()) "Let's set up your study shelf" else "What are we studying today?"
                        RECENT -> "Pick up where you left off"
                        UNSORTED -> "Files without a subject yet"
                        else -> "$count file${if (count == 1) "" else "s"}"
                    },
                    compact = !wide,
                    query = query, onQuery = { query = it },
                    onSettings = { settings = true },
                )
                if (!wide) {
                    SubjectChips(data.subjects, filter, onSelect = { filter = it }, onNew = { newSubject = true }, onMenu = { subjectMenu = it }, onSettings = { settings = true })
                }
                if (data.docs.isEmpty()) {
                    EmptyLibrary { onImport(currentSubject?.id) }
                } else {
                    val hp = if (wide) 24.dp else 16.dp
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (wide) 170.dp else 140.dp),
                        contentPadding = PaddingValues(start = hp, end = hp, top = 8.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 14.dp),
                        verticalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (continueDoc != null) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                                ContinueCard(continueDoc, data.subjects.firstOrNull { it.id == continueDoc.subjectId }, compact = !wide) { onOpen(continueDoc.id, backLabel) }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "section") {
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (filter == ALL) "Your files" else "Files",
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (docs.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    if (query.isNotBlank()) "Nothing matches \"$query\"." else "Nothing here yet. Tap Import to add files to this subject.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 32.dp),
                                )
                            }
                        }
                        items(docs, key = { it.id }) { d ->
                            DocCard(d, data.subjects.firstOrNull { it.id == d.subjectId }, actions)
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { onImport(currentSubject?.id) },
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text(if (currentSubject != null && wide) "Import to ${currentSubject.name}" else "Import") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(if (wide) 28.dp else 18.dp),
        )

        if (importing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp) {
                    Column(Modifier.padding(28.dp).width(300.dp)) {
                        Text("Importing…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Word, PowerPoint and photos are turned into PDFs so you can mark them up.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    // ---------------- dialogs
    if (settings) app.lilone.ui.SettingsDialog(onDismiss = { settings = false })
    if (newSubject) {
        NameDialog("New subject", "e.g. Anatomy", "", "Create", onDismiss = { newSubject = false }) {
            val s = repo.addSubject(it); newSubject = false; filter = s.id
        }
    }
    renameDoc?.let { d ->
        NameDialog("Rename", "Title", d.title, "Rename", onDismiss = { renameDoc = null }) { repo.renameDoc(d.id, it); renameDoc = null }
    }
    renameSubject?.let { s ->
        NameDialog("Rename subject", "Name", s.name, "Rename", onDismiss = { renameSubject = null }) { repo.renameSubject(s.id, it); renameSubject = null }
    }
    moveDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { moveDoc = null },
            title = { Text("Move \"${d.title}\" to") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MoveRow("Unsorted", null, d.subjectId == null) { repo.moveDoc(d.id, null); moveDoc = null }
                    data.subjects.forEach { s ->
                        MoveRow(s.name, Color(s.color), d.subjectId == s.id) { repo.moveDoc(d.id, s.id); moveDoc = null }
                    }
                    if (data.subjects.isEmpty()) Text("Create a subject first (e.g. Anatomy, Physiology).", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { moveDoc = null; newSubject = true }) { Text("New subject") } },
            dismissButton = { TextButton(onClick = { moveDoc = null }) { Text("Cancel") } },
        )
    }
    deleteDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteDoc = null },
            title = { Text("Delete \"${d.title}\"?") },
            text = { Text("The file and all its highlights and notes will be removed from Lil One. This can't be undone.") },
            confirmButton = { TextButton(onClick = { repo.deleteDoc(d.id); deleteDoc = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteDoc = null }) { Text("Cancel") } },
        )
    }
    subjectMenu?.let { s ->
        AlertDialog(
            onDismissRequest = { subjectMenu = null },
            title = { Text(s.name) },
            text = { Text("Deleting a subject keeps its files. They move to Unsorted.") },
            confirmButton = { TextButton(onClick = { subjectMenu = null; renameSubject = s }) { Text("Rename") } },
            dismissButton = {
                TextButton(onClick = { repo.deleteSubject(s.id); subjectMenu = null }) { Text("Delete subject", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

private class DocActions(
    val open: (String) -> Unit,
    val rename: (Doc) -> Unit,
    val move: (Doc) -> Unit,
    val share: (Doc) -> Unit,
    val saveCopy: (Doc) -> Unit,
    val delete: (Doc) -> Unit,
)

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "Late night study session"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

/** The Lil One mark on its burgundy tile, plus the wordmark. */
@Composable
fun BrandLogo(size: androidx.compose.ui.unit.Dp = 40.dp, showText: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(WineGradient),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.fillMaxSize().padding(size * 0.12f))
        }
        if (showText) {
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Lil One", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, lineHeight = 22.sp)
                Text("PDF & Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, letterSpacing = 0.6.sp)
            }
        }
    }
}

@Composable
private fun ThemeToggle() {
    val context = LocalContext.current
    val dark = AppTheme.isDark()
    IconButton(onClick = { AppTheme.set(context, if (dark) ThemeMode.Light else ThemeMode.Dark) }) {
        Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (dark) "Switch to light mode" else "Switch to dark mode")
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(50), color = scheme.surfaceContainerHigh, modifier = modifier.height(48.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search your files", color = scheme.onSurfaceVariant)
                androidx.compose.foundation.text.BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun SubjectRail(
    subjects: List<Subject>,
    docs: List<Doc>,
    filter: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onMenu: (Subject) -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(264.dp).fillMaxHeight()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 14.dp).verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(start = 8.dp, top = 22.dp, bottom = 22.dp)) { BrandLogo() }
            RailItem("All files", Icons.Outlined.SpaceDashboard, null, docs.size, filter == ALL) { onSelect(ALL) }
            RailItem("Recent", Icons.Outlined.History, null, null, filter == RECENT) { onSelect(RECENT) }
            RailItem("Unsorted", Icons.Outlined.Inbox, null, docs.count { it.subjectId == null }, filter == UNSORTED) { onSelect(UNSORTED) }
            Text(
                "SUBJECTS", Modifier.padding(start = 12.dp, top = 22.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subjects.forEach { s ->
                RailItem(
                    s.name, if (filter == s.id) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, Color(s.color),
                    docs.count { it.subjectId == s.id }, filter == s.id,
                    onLongPress = { onMenu(s) },
                ) { onSelect(s.id) }
            }
            RailItem("New subject", Icons.Outlined.CreateNewFolder, null, null, false, onClick = onNew)
            Spacer(Modifier.height(18.dp))
            RailItem("Settings", Icons.Outlined.Settings, null, null, false, onClick = onSettings)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    tint: Color?,
    count: Int?,
    selected: Boolean,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint ?: if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        )
        if (count != null) Text("$count", color = scheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        if (onLongPress != null && selected) {
            IconButton(onClick = onLongPress, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreVert, "Subject options", modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun SubjectChips(
    subjects: List<Subject>,
    filter: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onMenu: (Subject) -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(filter == ALL, onClick = { onSelect(ALL) }, label = { Text("All") })
        FilterChip(filter == RECENT, onClick = { onSelect(RECENT) }, label = { Text("Recent") })
        FilterChip(filter == UNSORTED, onClick = { onSelect(UNSORTED) }, label = { Text("Unsorted") })
        subjects.forEach { s ->
            FilterChip(
                filter == s.id,
                onClick = { if (filter == s.id) onMenu(s) else onSelect(s.id) },
                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(s.color))) },
                label = { Text(s.name) },
            )
        }
        FilterChip(false, onClick = onNew, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) }, label = { Text("Subject") })
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String,
    compact: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSettings: () -> Unit,
) {
    if (compact) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(38.dp)
                Spacer(Modifier.weight(1f))
                ThemeToggle()
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
            }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            app.lilone.ui.TrialChip(Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(if (app.lilone.data.License.trialEndsAt != null) 6.dp else 14.dp))
            SearchField(query, onQuery, Modifier.fillMaxWidth().padding(end = 8.dp))
        }
    } else {
        Row(Modifier.padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            app.lilone.ui.TrialChip(Modifier.padding(end = 12.dp))
            SearchField(query, onQuery, Modifier.width(320.dp))
            Spacer(Modifier.width(6.dp))
            ThemeToggle()
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLogo(96.dp, showText = false)
            Spacer(Modifier.height(22.dp))
            Text("Your study shelf is empty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Import PDFs, lecture slides, Word files or photos of your notes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onImport) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Import files") }
        }
    }
}

@Composable
private fun rememberThumb(d: Doc, file: File): ImageBitmap? {
    val v by produceState<ImageBitmap?>(null, d.id, d.lastOpened, file.exists()) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
    }
    return v
}

@Composable
private fun Thumb(d: Doc, modifier: Modifier) {
    val context = LocalContext.current
    val repo = remember { LibraryRepo.get(context) }
    val img = rememberThumb(d, repo.thumb(d.id))
    Box(
        modifier
            .shadow(4.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
    ) {
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.Center).size(40.dp))
        if (d.pageCount > 0 && d.lastOpened > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(((d.lastPage + 1f) / d.pageCount).coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.secondary))
            }
        }
    }
}

@Composable
private fun DocCard(d: Doc, subject: Subject?, actions: DocActions) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { actions.open(d.id) }.padding(4.dp)) {
        Thumb(d, Modifier.fillMaxWidth().aspectRatio(0.76f))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subject != null) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(subject.color)))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        listOfNotNull(subject?.name, if (d.pageCount > 0) "${d.pageCount} pages" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, "Options", Modifier.size(18.dp)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { menu = false; actions.rename(d) })
                    DropdownMenuItem(text = { Text("Move to subject") }, leadingIcon = { Icon(Icons.Outlined.Folder, null) }, onClick = { menu = false; actions.move(d) })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; actions.share(d) })
                    DropdownMenuItem(text = { Text("Save a copy to device") }, leadingIcon = { Icon(Icons.Outlined.Save, null) }, onClick = { menu = false; actions.saveCopy(d) })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; actions.delete(d) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(d: Doc, subject: Subject?, compact: Boolean, onOpen: () -> Unit) {
    val progress = if (d.pageCount > 0) ((d.lastPage + 1f) / d.pageCount).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(WineGradient)
            .clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(if (compact) 16.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(d, Modifier.width(if (compact) 64.dp else 80.dp).aspectRatio(0.76f))
            Spacer(Modifier.width(if (compact) 16.dp else 20.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUE READING", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, color = Blush)
                Spacer(Modifier.height(4.dp))
                Text(
                    d.title, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(subject?.name, if (d.pageCount > 0) "Page ${d.lastPage + 1} of ${d.pageCount}" else null).joinToString(" · "),
                    color = Cream.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium,
                )
                if (d.pageCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(if (compact) 1f else 0.6f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Cream.copy(alpha = 0.22f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(RoundedCornerShape(3.dp)).background(Blush))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (compact) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Open", tint = Cream)
            } else {
                Button(
                    onClick = onOpen,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Cream, contentColor = WineDeep),
                ) { Text("Open", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun MoveRow(label: String, color: Color?, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        else Icon(Icons.Outlined.Inbox, null, Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
        if (current) Text("current", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
fun NameDialog(title: String, hint: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, placeholder = { Text(hint) }, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton(onClick = { onConfirm(v) }, enabled = v.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
