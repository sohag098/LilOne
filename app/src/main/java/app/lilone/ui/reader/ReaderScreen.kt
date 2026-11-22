package app.lilone.ui.reader

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.lilone.data.LibraryRepo
import app.lilone.data.Sharing
import app.lilone.ui.theme.AppTheme
import app.lilone.ui.theme.LilOneTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    docId: String,
    onBack: () -> Unit,
    /** Shown next to the back arrow: the folder it was opened from, or the other app's name. */
    backLabel: String = "Library",
    fromOtherApp: Boolean = false,
    onLibrary: () -> Unit = onBack,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    // The reader's ViewModel lives exactly as long as this screen: leaving the reader clears it,
    // which saves, closes the PDF and refreshes the library thumbnail.
    val owner = remember(docId) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm: ReaderViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "reader-$docId",
        factory = viewModelFactory { initializer { ReaderViewModel(app, docId) } },
    )
    val dark = vm.readMode == ReadMode.Night || AppTheme.isDark()
    LilOneTheme(dark = dark) { ReaderContent(vm, onBack, backLabel, fromOtherApp, onLibrary) }
}

@Composable
private fun ReaderContent(vm: ReaderViewModel, onBack: () -> Unit, backLabel: String, fromOtherApp: Boolean, onLibrary: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val library by LibraryRepo.get(context).state.collectAsState()
    val doc = library.docs.firstOrNull { it.id == vm.docId }
    var goTo by remember { mutableStateOf(false) }

    BackHandler {
        when {
            vm.panel != null -> vm.panel = null
            vm.editMode -> vm.toggleEdit()
            else -> { vm.saveNow(); onBack() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveNow() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val f = vm.exportCopy()
            val ok = Sharing.writeTo(context, f, uri)
            snackbar.showSnackbar(if (ok) "Saved a copy" else "Couldn't save the copy")
        }
    }

    val background = when (vm.readMode) {
        ReadMode.Day -> MaterialTheme.colorScheme.surfaceContainer
        ReadMode.Sepia -> Color(0xFFEDE3D1)
        ReadMode.Night -> Color(0xFF0F0E10)
    }

    Box(Modifier.fillMaxSize().background(background)) {
        when {
            vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            vm.error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't open this file", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(vm.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text(if (fromOtherApp) "Back to $backLabel" else "Back to library") }
            }
            vm.locked -> PasswordDialog(onUnlock = vm::unlock, onCancel = onBack)
            else -> {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = vm.currentPage)
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo.visibleItemsInfo
                        val first = info.firstOrNull()
                        if (first == null) listState.firstVisibleItemIndex
                        else if (listState.firstVisibleItemScrollOffset > first.size * 0.6f && info.size > 1) info[1].index
                        else first.index
                    }.distinctUntilChanged().collect { vm.onPageChanged(it) }
                }
                LaunchedEffect(listState) { vm.scrollTo.collect { listState.scrollToItem(it) } }

                PagesView(vm, listState, topPadding = 72.dp + statusBarHeight())

                // right-edge quick scroll: hidden while drawing (so the pen never grabs it) and while a side
                // panel is open; stops above the page-number pill in the bottom corner
                if (!vm.editMode && vm.panel == null) {
                    QuickScroller(
                        listState, vm.boxes.size, showWithChrome = vm.chromeVisible,
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().navigationBarsPadding()
                            .padding(top = 80.dp, bottom = 84.dp),
                    )
                }

                // page number pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.78f),
                    modifier = Modifier
                        .align(if (vm.editMode) Alignment.TopEnd else Alignment.BottomEnd)
                        .then(if (vm.editMode) Modifier.statusBarsPadding().padding(top = 76.dp) else Modifier.navigationBarsPadding())
                        .padding(18.dp)
                        .clickable { goTo = true },
                ) {
                    Text(
                        "${vm.currentPage + 1} / ${vm.boxes.size}",
                        Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                AnimatedVisibility(
                    visible = vm.chromeVisible || vm.editMode,
                    enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    TopBar(vm, doc?.title ?: "", doc?.bookmarks ?: emptyList(), backLabel, fromOtherApp,
                        onBack = { vm.saveNow(); onBack() },
                        onLibrary = { vm.saveNow(); onLibrary() },
                        onShare = { scope.launch { Sharing.sharePdf(context, vm.exportCopy()) } },
                        onSaveCopy = { saveCopy.launch(Sharing.safeName(doc?.title ?: "Document") + ".pdf") })
                }

                // On a phone the side panel covers the whole page, so the toolbar steps aside while it is open
                val roomForBoth = LocalConfiguration.current.screenWidthDp >= 940
                AnimatedVisibility(
                    visible = vm.editMode && (vm.panel == null || roomForBoth),
                    enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(start = 8.dp, bottom = 16.dp, end = if (vm.panel != null) 478.dp else 8.dp),
                ) { EditToolbar(vm) }

                AnimatedVisibility(
                    visible = vm.panel != null,
                    enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) { SidePanel(vm, doc?.bookmarks ?: emptyList(), Modifier.statusBarsPadding().padding(top = 64.dp)) }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (vm.editMode) 150.dp else 16.dp))
    }

    vm.textDraft?.let { d -> TextEntryDialog(d, onDone = { vm.commitText(d, it) }, onDismiss = { vm.textDraft = null }) }
    vm.selected?.let { AnnotationDialog(vm, it) }
    if (vm.showSettings) app.lilone.ui.SettingsDialog(onDismiss = { vm.showSettings = false; vm.keyVersion++ })
    if (goTo) GoToPageDialog(vm.boxes.size, onGo = { vm.jumpTo(it); goTo = false }, onDismiss = { goTo = false })
}

@Composable
private fun statusBarHeight() = with(androidx.compose.ui.platform.LocalDensity.current) {
    WindowInsets.statusBars.getTop(this).toDp()
}

@Composable
private fun TopBar(
    vm: ReaderViewModel,
    title: String,
    bookmarks: List<Int>,
    backLabel: String,
    fromOtherApp: Boolean,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onShare: () -> Unit,
    onSaveCopy: () -> Unit,
) {
    var modeMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val marked = vm.currentPage in bookmarks
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.97f), shadowElevation = 4.dp) {
        Column {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Phones get a compact bar: the less-used actions move into the ⋮ menu
                val compact = maxWidth < 640.dp
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackChip(backLabel, maxLabel = if (compact) 84.dp else 160.dp, onClick = onBack)
                    Text(
                        title, Modifier.weight(1f).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { if (vm.panel == Panel.AI) vm.panel = null else vm.openPanel(Panel.AI) }) {
                        Icon(Icons.Outlined.AutoAwesome, "Study assistant", tint = MaterialTheme.colorScheme.primary)
                    }
                    if (!compact) {
                        IconButton(onClick = { vm.openPanel(Panel.Search) }) { Icon(Icons.Outlined.Search, "Search") }
                        IconButton(onClick = { vm.openPanel(vm.panel?.takeIf { it != Panel.AI } ?: Panel.Contents) }) {
                            Icon(Icons.AutoMirrored.Outlined.MenuBook, "Contents, bookmarks and highlights")
                        }
                        IconButton(onClick = { vm.toggleBookmark() }) {
                            Icon(
                                if (marked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "Bookmark page",
                                tint = if (marked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Box {
                            IconButton(onClick = { modeMenu = true }) { Icon(Icons.Outlined.Brightness6, "Reading mode") }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                ModeItems(vm) { modeMenu = false }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            if (compact) {
                                DropdownMenuItem(text = { Text("Search") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, onClick = { more = false; vm.openPanel(Panel.Search) })
                                DropdownMenuItem(text = { Text("Contents & highlights") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }, onClick = { more = false; vm.openPanel(Panel.Contents) })
                                DropdownMenuItem(
                                    text = { Text(if (marked) "Remove bookmark" else "Bookmark this page") },
                                    leadingIcon = { Icon(if (marked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null) },
                                    onClick = { more = false; vm.toggleBookmark() },
                                )
                                HorizontalDivider()
                                ModeItems(vm) { more = false }
                                HorizontalDivider()
                            }
                            if (fromOtherApp) {
                                DropdownMenuItem(text = { Text("Open Lil One library") }, leadingIcon = { Icon(Icons.Outlined.LocalLibrary, null) }, onClick = { more = false; onLibrary() })
                            }
                            DropdownMenuItem(text = { Text("Share PDF") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { more = false; onShare() })
                            DropdownMenuItem(text = { Text("Save a copy to device") }, leadingIcon = { Icon(Icons.Outlined.Save, null) }, onClick = { more = false; onSaveCopy() })
                            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { more = false; vm.showSettings = true })
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    val pad = PaddingValues(horizontal = if (compact) 14.dp else 18.dp)
                    if (vm.editMode) {
                        Button(onClick = { vm.toggleEdit() }, contentPadding = pad) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Done")
                        }
                    } else {
                        FilledTonalButton(onClick = { vm.toggleEdit() }, contentPadding = pad) {
                            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit")
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

/** "‹ Anatomy" / "‹ Files": back arrow plus where it goes, like iOS. */
@Composable
private fun BackChip(label: String, maxLabel: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 2.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClickLabel = "Back to $label", onClick = onClick)
            .height(48.dp)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            label, Modifier.widthIn(max = maxLabel),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModeItems(vm: ReaderViewModel, close: () -> Unit) {
    ModeItem("Day", Icons.Outlined.LightMode, vm.readMode == ReadMode.Day) { vm.readMode = ReadMode.Day; close() }
    ModeItem("Sepia", Icons.Outlined.WbTwilight, vm.readMode == ReadMode.Sepia) { vm.readMode = ReadMode.Sepia; close() }
    ModeItem("Night", Icons.Outlined.DarkMode, vm.readMode == ReadMode.Night) { vm.readMode = ReadMode.Night; close() }
}

@Composable
private fun ModeItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}
