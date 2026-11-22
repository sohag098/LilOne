package app.lilone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.FilterChip
import app.lilone.ui.theme.AppTheme
import app.lilone.ui.theme.ThemeMode
import app.lilone.data.Gemini
import app.lilone.data.GeminiError
import app.lilone.data.GeminiSettings
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(GeminiSettings.key(context)) }
    var model by remember { mutableStateOf(GeminiSettings.model(context)) }
    val savedMode = remember { GeminiSettings.mode(context) }
    var mode by remember { mutableStateOf(savedMode) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var allModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showKey by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var modelMenu by remember { mutableStateOf(false) }

    fun check() {
        checking = true; status = null
        scope.launch {
            try {
                val list = Gemini.listModels(key.trim())
                allModels = list
                models = Gemini.chatModels(list)
                if (model !in models) model = Gemini.pickBest(list) ?: models.firstOrNull() ?: ""
                status = true to when (mode) {
                    GeminiSettings.FAST -> "Key works. Fast would use ${Gemini.pickFor(mode, list)}."
                    GeminiSettings.CUSTOM -> "Key works. Pick a model below."
                    else -> "Key works. Auto would use ${Gemini.pickFor(mode, list)}."
                }
            } catch (e: GeminiError) {
                status = false to (e.message ?: "Couldn't check the key")
            } finally {
                checking = false
            }
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      // imePadding keeps the dialog (and its Save button) above the on-screen keyboard
      Box(Modifier.fillMaxSize().imePadding().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.92f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))

                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Auto follows your device's dark mode. Inside a PDF you can also pick Day, Sepia or Night pages.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = AppTheme.mode == m,
                            onClick = { AppTheme.set(context, m) },
                            label = { Text(m.label) },
                            leadingIcon = {
                                Icon(
                                    when (m) { ThemeMode.System -> Icons.Outlined.BrightnessAuto; ThemeMode.Light -> Icons.Outlined.LightMode; ThemeMode.Dark -> Icons.Outlined.DarkMode },
                                    null, Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Study assistant (Gemini)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Explain, summarise, quiz and chat use Google's Gemini. It needs your own free API key: " +
                        "sign in with Google on AI Studio, tap \"Create API key\", copy it and paste it below.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Gemini.KEY_PAGE))) }
                }) {
                    Text("Get a free key on AI Studio"); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim(); status = null },
                    label = { Text("Gemini API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "Hide key" else "Show key")
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    // Password type: the keyboard won't learn or suggest the key
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { check() }, enabled = key.isNotBlank() && !checking) { Text("Check key") }
                    if (checking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
                status?.let { (ok, msg) ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(msg, fontSize = 14.sp, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                }

                // ---- which Gemini model ----
                Spacer(Modifier.height(18.dp))
                Text("Model", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == GeminiSettings.AUTO, onClick = { mode = GeminiSettings.AUTO },
                        label = { Text("Auto") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = mode == GeminiSettings.FAST, onClick = { mode = GeminiSettings.FAST },
                        label = { Text("Fast") }, leadingIcon = { Icon(Icons.Outlined.Bolt, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = mode == GeminiSettings.CUSTOM,
                        onClick = { mode = GeminiSettings.CUSTOM; if (models.isEmpty() && key.isNotBlank()) check() },
                        label = { Text("Choose") }, leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (mode) {
                        GeminiSettings.FAST -> "Uses Flash-Lite. Replies come quicker and are a little shorter. Good when the assistant feels slow."
                        GeminiSettings.CUSTOM -> "Pick any chat model your key can use."
                        else -> "Uses the newest Flash model and keeps it up to date. Best answers. Recommended."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                )
                if (mode == GeminiSettings.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    when {
                        key.isBlank() -> Text("Add your key first.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                        models.isEmpty() -> if (!checking) TextButton(onClick = { check() }) { Text("Load models") }
                        else -> Box {
                            OutlinedButton(onClick = { modelMenu = true }) {
                                Text(model.ifBlank { "Choose a model" }); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore, null)
                            }
                            DropdownMenu(modelMenu, onDismissRequest = { modelMenu = false }) {
                                models.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m, fontWeight = if (m == model) FontWeight.SemiBold else FontWeight.Normal) },
                                        trailingIcon = { if (m == model) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
                                        onClick = { model = m; modelMenu = false },
                                    )
                                }
                            }
                        }
                    }
                } else if (mode == savedMode && GeminiSettings.model(context).isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Now using ${GeminiSettings.model(context)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "If Google is busy, the assistant switches to a lighter model by itself for an hour.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )

                Spacer(Modifier.height(16.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                    Text(
                        "Privacy: when you use the assistant, the text (and for scans, page images) you ask about is sent to Google. " +
                            "On the free tier Google may use it to improve its products, so don't send patient names or other private details. " +
                            "Everything else in Lil One stays on this device.",
                        Modifier.padding(14.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ---- license ----
                Spacer(Modifier.height(24.dp))
                Text("License", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                val licKey = remember { app.lilone.data.License.key(context) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (licKey != null) "Activated · " + app.lilone.data.License.masked(licKey) else "Activated",
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "One key works on 2 devices. Moving to a new phone? Remove this device first to free its slot.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
                var confirmRemove by remember { mutableStateOf(false) }
                var removing by remember { mutableStateOf(false) }
                var removeError by remember { mutableStateOf<String?>(null) }
                TextButton(
                    onClick = { confirmRemove = true }, enabled = !removing,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                ) { Text("Remove this device", color = MaterialTheme.colorScheme.error) }
                removeError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (confirmRemove) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmRemove = false },
                        title = { Text("Remove this device?") },
                        text = { Text("Lil One will lock on this device and its slot is freed, so you can activate another phone or tablet. Your files stay here; enter your key again to unlock.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmRemove = false; removing = true; removeError = null
                                scope.launch {
                                    val ok = app.lilone.data.License.removeDevice(context)
                                    removing = false
                                    if (ok) onDismiss() else removeError = "Couldn't reach the license server. Check your internet and try again."
                                }
                            }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text("Lil One " + (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Free software under the GNU Affero General Public License v3, with no warranty. " +
                        "PDF engine: MuPDF by Artifex Software (AGPL v3).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (app.lilone.SOURCE_URL.isNotBlank()) {
                    TextButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.lilone.SOURCE_URL))) } },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    ) { Text("Source code"); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        GeminiSettings.saveSettings(
                            context, key, mode,
                            if (key.trim() == GeminiSettings.key(context) || models.isNotEmpty()) model else "",
                            picked = if (mode != GeminiSettings.CUSTOM && allModels.isNotEmpty()) Gemini.pickFor(mode, allModels) else null,
                        )
                        onDismiss()
                    }) { Text("Save") }
                }
            }
        }
      }
    }
}
