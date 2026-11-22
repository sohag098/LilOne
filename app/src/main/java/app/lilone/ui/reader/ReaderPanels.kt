package app.lilone.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lilone.data.Sharing
import app.lilone.pdf.AnnotInfo
import app.lilone.pdf.AnnotKind

@Composable
fun SidePanel(vm: ReaderViewModel, bookmarks: List<Int>, modifier: Modifier = Modifier) {
    val panel = vm.panel ?: return
    Surface(
        modifier = modifier.widthIn(max = 470.dp).fillMaxWidth().fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
    ) {
        if (panel == Panel.AI) {
            Box(Modifier.fillMaxSize().padding(top = 6.dp)) { AiPanel(vm) }
            return@Surface
        }
        Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Panel.entries.filter { it != Panel.AI }.forEach { p ->
                        FilterChip(
                            selected = p == panel,
                            onClick = { vm.openPanel(p) },
                            label = { Text(p.title()) },
                        )
                    }
                }
                IconButton(onClick = { vm.panel = null }) { Icon(Icons.Outlined.Close, "Close") }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            when (panel) {
                Panel.Contents -> ContentsTab(vm)
                Panel.Bookmarks -> BookmarksTab(vm, bookmarks)
                Panel.Notes -> NotesTab(vm)
                Panel.Search -> SearchTab(vm)
                Panel.AI -> {}
            }
        }
    }
}

private fun Panel.title() = when (this) {
    Panel.Contents -> "Contents"
    Panel.Bookmarks -> "Bookmarks"
    Panel.Notes -> "Highlights"
    Panel.Search -> "Search"
    Panel.AI -> "Assistant"
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ContentsTab(vm: ReaderViewModel) {
    val items = vm.outline
    when {
        items == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        items.isEmpty() -> EmptyHint("This PDF has no table of contents. Use Bookmarks to mark chapters yourself.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(items) { o ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = o.page >= 0) { vm.jumpTo(o.page) }
                        .padding(start = (16 + o.level * 16).dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        o.title, Modifier.weight(1f),
                        fontWeight = if (o.level == 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (o.page >= 0) Text("${o.page + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun BookmarksTab(vm: ReaderViewModel, bookmarks: List<Int>) {
    Column(Modifier.fillMaxSize()) {
        val marked = vm.currentPage in bookmarks
        FilledTonalButton(onClick = { vm.toggleBookmark() }, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Icon(if (marked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null)
            Spacer(Modifier.width(8.dp))
            Text(if (marked) "Remove bookmark from page ${vm.currentPage + 1}" else "Bookmark page ${vm.currentPage + 1}")
        }
        if (bookmarks.isEmpty()) EmptyHint("No bookmarks yet.")
        else LazyColumn {
            items(bookmarks) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.jumpTo(p) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Bookmark, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(14.dp))
                    Text("Page ${p + 1}", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NotesTab(vm: ReaderViewModel) {
    val context = LocalContext.current
    val all = vm.notes
    var filter by rememberSaveable { mutableStateOf(-1) }
    if (all == null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)); return }
    if (all.isEmpty()) { EmptyHint("Highlights, notes and drawings you add show up here, so you can revise a whole chapter at a glance."); return }
    val used = all.map { paletteIndex(it.color) }.filter { it >= 0 }.distinct().sorted()
    val list = if (filter < 0) all else all.filter { paletteIndex(it.color) == filter }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = filter < 0, onClick = { filter = -1 }, label = { Text("All ${all.size}") })
            used.forEach { i ->
                FilterChip(
                    selected = filter == i, onClick = { filter = i },
                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(Palette[i]))) },
                    label = { Text(PaletteNames[i]) },
                )
            }
            IconButton(onClick = { Sharing.shareText(context, "${vm.doc?.title}: notes", vm.notesAsText()) }) {
                Icon(Icons.Outlined.IosShare, "Export notes")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            var lastPage = -1
            list.forEach { a ->
                if (a.page != lastPage) {
                    lastPage = a.page
                    item(key = "h${a.page}") {
                        Text(
                            "Page ${a.page + 1}", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                item(key = "${a.page}-${a.index}") { NoteRow(a) { vm.jumpTo(a.page); vm.selected = null } }
            }
        }
    }
}

@Composable
private fun NoteRow(a: AnnotInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 3.dp).width(4.dp).heightIn(min = 18.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Color(a.color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.kind.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val text = a.contents.ifBlank { if (a.kind == AnnotKind.Ink) "Drawing" else "" }
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun AnnotKind.label() = when (this) {
    AnnotKind.Highlight -> "Highlight"
    AnnotKind.Underline -> "Underline"
    AnnotKind.Strike -> "Strikethrough"
    AnnotKind.Ink -> "Drawing"
    AnnotKind.Text -> "Text"
    AnnotKind.Note -> "Note"
    AnnotKind.Box -> "Box"
    AnnotKind.Arrow -> "Arrow"
    AnnotKind.Other -> "Annotation"
}

@Composable
private fun SearchTab(vm: ReaderViewModel) {
    var q by rememberSaveable { mutableStateOf(vm.searchQuery) }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (vm.searchQuery.isEmpty()) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
            placeholder = { Text("Search in this PDF") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(q); keyboard?.hide() }),
        )
        if (vm.searching) {
            LinearProgressIndicator(
                progress = { if (vm.boxes.isEmpty()) 0f else vm.searchedPages / vm.boxes.size.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (vm.searchQuery.isNotBlank()) {
            Text(
                if (vm.searching) "${vm.searchResults.size} found so far…" else "${vm.searchResults.size} results",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.searchResults) { h ->
                val active = h == vm.activeHit
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                        .clickable { vm.showHit(h) }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text("Page ${h.page + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(h.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
fun TextEntryDialog(draft: TextDraft, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.isNote) "Add a note" else "Add text") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
                placeholder = { Text(if (draft.isNote) "e.g. Ask about this in the tutorial" else "Type here") },
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AnnotationDialog(vm: ReaderViewModel, a: AnnotInfo) {
    var text by remember(a.page, a.index) { mutableStateOf(a.contents) }
    val editable = a.kind in setOf(AnnotKind.Note, AnnotKind.Text, AnnotKind.Highlight, AnnotKind.Underline, AnnotKind.Strike)
    AlertDialog(
        onDismissRequest = { vm.selected = null },
        title = { Text("${a.kind.label()} · page ${a.page + 1}") },
        text = {
            Column {
                if (editable) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        label = { Text(if (a.kind == AnnotKind.Note || a.kind == AnnotKind.Text) "Text" else "Text / your note") },
                        shape = RoundedCornerShape(14.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                if (a.contents.isNotBlank() && a.kind in setOf(AnnotKind.Highlight, AnnotKind.Underline, AnnotKind.Strike, AnnotKind.Note, AnnotKind.Text)) {
                    FilledTonalButton(onClick = {
                        vm.ai.explainText(a.contents, a.page)
                        vm.selected = null
                        vm.openPanel(Panel.AI)
                    }) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Explain with AI")
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val current = paletteIndex(a.color)
                    Palette.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(if (i == current) 32.dp else 28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable { vm.updateSelected(color = c) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (editable && text != a.contents) vm.updateSelected(contents = text)
                vm.selected = null
            }) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = { vm.deleteSelected() }) {
                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
fun PasswordDialog(onUnlock: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("This PDF is locked") },
        text = {
            OutlinedTextField(
                value = pw, onValueChange = { pw = it }, singleLine = true,
                label = { Text("Password") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onUnlock(pw) }),
            )
        },
        confirmButton = { TextButton(onClick = { onUnlock(pw) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Back") } },
    )
}

@Composable
fun GoToPageDialog(count: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf("") }
    val n = v.toIntOrNull()
    val ok = n != null && n in 1..count
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = v, onValueChange = { s -> v = s.filter { it.isDigit() }.take(6) }, singleLine = true,
                label = { Text("1 – $count") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (ok) onGo(n!! - 1) }),
            )
        },
        confirmButton = { TextButton(onClick = { onGo(n!! - 1) }, enabled = ok) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
