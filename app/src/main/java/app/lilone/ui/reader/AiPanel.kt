package app.lilone.ui.reader

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AiPanel(vm: ReaderViewModel) {
    val ai = vm.ai
    @Suppress("UNUSED_VARIABLE") val keyCheck = vm.keyVersion // re-read the key after Settings closes
    LaunchedEffect(vm.keyVersion) { if (!ai.busy) ai.refreshModel() } // and show the newly chosen model
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Study assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (ai.modelName.isNotBlank()) Text(ai.modelName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ai.messages.isNotEmpty()) IconButton(onClick = { ai.clear() }) { Icon(Icons.Outlined.DeleteSweep, "Clear chat") }
            IconButton(onClick = { vm.showSettings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
            IconButton(onClick = { vm.panel = null }) { Icon(Icons.Outlined.Close, "Close") }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

        if (!ai.hasKey()) {
            KeySetup { vm.showSettings = true }
            return@Column
        }

        val list = rememberLazyListState()
        val lastLen = ai.messages.lastOrNull()?.text?.length ?: 0
        LaunchedEffect(ai.messages.size, lastLen / 200) {
            if (ai.messages.isNotEmpty()) list.animateScrollToItem(ai.messages.size - 1)
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ai.messages.isEmpty()) item { Intro() }
            items(ai.messages, key = { it.id }) { m -> MessageView(vm, m) }
        }
        QuickActions(vm)
        InputBar(vm)
    }
}

@Composable
private fun KeySetup(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Turn on the study assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "It can explain anything you select, summarise pages or chapters, quiz you and answer questions about this PDF.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Text("1.  Get a free Gemini API key from Google AI Studio.")
        Spacer(Modifier.height(6.dp))
        Text("2.  Paste it in Settings. You only do this once.")
        Spacer(Modifier.height(22.dp))
        Button(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(8.dp)); Text("Open Settings") }
    }
}

@Composable
private fun Intro() {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Hi! What should we study?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val tips = listOf(
            "Ask anything about this page, the chapter or the whole PDF.",
            "Use the buttons below to summarise, get a quiz or make flashcards.",
            "In Edit mode, pick ✨ Ask AI and drag a box around any text, table or diagram to get it explained.",
            "Tap a highlight and choose Explain.",
            "Save any answer as a note on the page, so it shows up in Highlights.",
        )
        tips.forEach { Text("•  $it", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

@Composable
private fun MessageView(vm: ReaderViewModel, m: AiMessage) {
    val scheme = MaterialTheme.colorScheme
    if (m.user) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(color = scheme.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 340.dp)) {
                Column(Modifier.padding(12.dp)) {
                    m.image?.let {
                        Image(
                            it, null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(m.text, color = scheme.onPrimaryContainer)
                }
            }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    Surface(
        color = if (m.error) scheme.errorContainer else scheme.surfaceContainerLowest,
        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (m.pending && m.text.isEmpty()) {
                Text("Thinking…", color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (m.error) {
                Text(m.text, color = scheme.onErrorContainer)
            } else {
                if (m.quiz == null && m.cards == null) {
                    SelectionContainer { Markdown(AiAssistant.cleanMath(m.text)) { vm.jumpTo(it) } }
                } else {
                    Text(m.text, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    m.quiz?.let { QuizView(it) { p -> vm.jumpTo(p) } }
                    m.cards?.let { CardsView(it) }
                }
                if (m.pending) {
                    Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.ai.saveAsNote(m) }, enabled = !m.saved) {
                            Icon(Icons.Outlined.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (m.saved) "Saved on p. ${m.page + 1}" else "Save as note")
                        }
                        if (m.quiz == null && m.cards == null) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(AiAssistant.plain(m.text))) }) {
                                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActions(vm: ReaderViewModel) {
    val ai = vm.ai
    var quizMenu by remember { mutableStateOf(false) }
    var cardMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = { ai.summarise(chapter = false) }, enabled = !ai.busy, label = { Text("Summarise page") })
        AssistChip(onClick = { ai.summarise(chapter = true) }, enabled = !ai.busy, label = { Text("Summarise chapter") })
        Box {
            AssistChip(onClick = { quizMenu = true }, enabled = !ai.busy, label = { Text("Quiz me") }, trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp)) })
            DropdownMenu(quizMenu, onDismissRequest = { quizMenu = false }) {
                QuizSource.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { quizMenu = false; ai.quiz(s, flashcards = false) }) }
            }
        }
        Box {
            AssistChip(onClick = { cardMenu = true }, enabled = !ai.busy, label = { Text("Flashcards") }, trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp)) })
            DropdownMenu(cardMenu, onDismissRequest = { cardMenu = false }) {
                QuizSource.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { cardMenu = false; ai.quiz(s, flashcards = true) }) }
            }
        }
        AssistChip(onClick = { ai.ask("Explain this page in simple words, like a good tutor would.") }, enabled = !ai.busy, label = { Text("Explain page simply") })
    }
}

@Composable
private fun InputBar(vm: ReaderViewModel) {
    val ai = vm.ai
    var q by rememberSaveable { mutableStateOf("") }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun send() {
        if (ai.busy || q.isBlank()) return
        ai.ask(q); q = ""
        focus.clearFocus(); keyboard?.hide()
    }
    Column(Modifier.fillMaxWidth().imePadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Answer from", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AiScope.entries.forEach { s ->
                FilterChip(selected = ai.scope == s, onClick = { ai.scope = s }, label = { Text(s.label, fontSize = 12.sp) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = q, onValueChange = { q = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about ${ai.scope.label.lowercase()}…") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.width(6.dp))
            if (ai.busy) {
                IconButton(onClick = { ai.stop() }) { Icon(Icons.Outlined.Stop, "Stop", tint = MaterialTheme.colorScheme.error) }
            } else {
                IconButton(onClick = { send() }, enabled = q.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = if (q.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ quiz + flashcards

@Composable
private fun QuizView(qs: List<QuizQ>, onPage: (Int) -> Unit) {
    val picks = remember(qs) { mutableStateMapOf<Int, Int>() }
    val scheme = MaterialTheme.colorScheme
    val good = Color(0xFF2F9E44)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        qs.forEachIndexed { qi, q ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${qi + 1}. ${q.question}", fontWeight = FontWeight.SemiBold)
                q.options.forEachIndexed { oi, opt ->
                    val picked = picks[qi]
                    val border = when {
                        picked == null -> scheme.outlineVariant
                        oi == q.answer -> good
                        oi == picked -> scheme.error
                        else -> scheme.outlineVariant
                    }
                    val fill = when {
                        picked != null && oi == q.answer -> good.copy(alpha = 0.12f)
                        picked == oi -> scheme.error.copy(alpha = 0.10f)
                        else -> Color.Transparent
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(BorderStroke(1.5.dp, border), RoundedCornerShape(12.dp))
                            .background(fill)
                            .clickable(enabled = picked == null) { picks[qi] = oi }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text("${'A' + oi}.", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(26.dp))
                        Text(opt)
                    }
                }
                picks[qi]?.let { p ->
                    val right = p == q.answer
                    Text(
                        (if (right) "Correct! " else "Answer: ${'A' + q.answer}. ") + q.explanation,
                        color = if (right) good else scheme.onSurface, fontSize = 14.sp,
                    )
                    q.page?.let { pg ->
                        Text("Go to page $pg", color = scheme.primary, fontSize = 13.sp, modifier = Modifier.clickable { onPage(pg - 1) })
                    }
                }
            }
        }
        if (picks.size == qs.size) {
            val score = qs.indices.count { picks[it] == qs[it].answer }
            Surface(color = scheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Text(
                    "Score: $score / ${qs.size}" + if (score == qs.size) "  🎉" else "",
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CardsView(cards: List<Flashcard>) {
    var index by remember(cards) { mutableIntStateOf(0) }
    var flipped by remember(index) { mutableStateOf(false) }
    val c = cards[index]
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = if (flipped) scheme.secondaryContainer else scheme.primaryContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp).clip(RoundedCornerShape(18.dp)).clickable { flipped = !flipped },
        ) {
            Box(Modifier.padding(20.dp).heightIn(min = 130.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (flipped) "ANSWER" else "QUESTION", fontSize = 11.sp, letterSpacing = 1.2.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(if (flipped) c.back else c.front, style = MaterialTheme.typography.titleMedium)
                    if (flipped && c.page != null) {
                        Spacer(Modifier.height(6.dp)); Text("p. ${c.page}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { index-- }, enabled = index > 0) { Text("Previous") }
            Text("${index + 1} / ${cards.size}", fontSize = 13.sp)
            TextButton(onClick = { index++ }, enabled = index < cards.size - 1) { Text("Next") }
        }
    }
}

// ------------------------------------------------------------------ tiny Markdown renderer

private val pageRef = Regex("\\b(?:pp?\\.|[Pp]ages?|pg\\.)\\s?(\\d{1,4})(?:\\s?[–-]\\s?\\d{1,4})?")
private val inline = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)|`([^`]+)`")

@Composable
fun Markdown(text: String, onPage: (Int) -> Unit) {
    val link = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        text.lines().forEach { raw ->
            val indent = (raw.length - raw.trimStart().length) / 2
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(4.dp))
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(line) -> HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                line.startsWith("#") -> Text(
                    rich(line.trimStart('#').trim(), link, onPage),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Regex("^[-*•]\\s+").containsMatchIn(line) -> Row(Modifier.padding(start = (indent * 14).dp)) {
                    Text("•", modifier = Modifier.width(16.dp))
                    Text(rich(line.replaceFirst(Regex("^[-*•]\\s+"), ""), link, onPage))
                }
                Regex("^\\d+[.)]\\s+").containsMatchIn(line) -> Row(Modifier.padding(start = (indent * 14).dp)) {
                    val n = Regex("^(\\d+)[.)]").find(line)!!.groupValues[1]
                    Text("$n.", modifier = Modifier.width(24.dp))
                    Text(rich(line.replaceFirst(Regex("^\\d+[.)]\\s+"), ""), link, onPage))
                }
                else -> Text(rich(line, link, onPage))
            }
        }
    }
}

private fun rich(s: String, link: Color, onPage: (Int) -> Unit): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in inline.findAll(s)) {
        linkify(s.substring(i, m.range.first), link, onPage)
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { linkify(m.groupValues[1], link, onPage) }
            m.groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { linkify(m.groupValues[2], link, onPage) }
            m.groups[3] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { linkify(m.groupValues[3], link, onPage) }
            m.groups[4] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14000000))) { append(m.groupValues[4]) }
        }
        i = m.range.last + 1
    }
    linkify(s.substring(i), link, onPage)
}

/** Turns "(p. 12)" into a tappable link that jumps to that page. */
private fun AnnotatedString.Builder.linkify(s: String, link: Color, onPage: (Int) -> Unit) {
    var i = 0
    for (m in pageRef.findAll(s)) {
        append(s.substring(i, m.range.first))
        val page = m.groupValues[1].toInt()
        withLink(
            LinkAnnotation.Clickable(
                tag = "page$page",
                styles = TextLinkStyles(SpanStyle(color = link, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
                linkInteractionListener = { onPage(page - 1) },
            )
        ) { append(m.value) }
        i = m.range.last + 1
    }
    append(s.substring(i))
}
