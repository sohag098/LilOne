package app.lilone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lilone.data.License
import app.lilone.ui.library.BrandLogo
import kotlinx.coroutines.launch

/**
 * Key screen: shown when the trial is over (or a key stopped working), and from the trial chip ("I have a key"),
 * where [onClose] gives a way back to the app.
 */
@Composable
fun ActivationScreen(onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var key by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val shown = error ?: License.lockMessage
    val trialEnded = onClose == null && License.trialOver && License.lockMessage == null

    fun activate() {
        if (key.isBlank() || busy) return
        keyboard?.hide()
        busy = true; error = null
        scope.launch {
            val r = License.activate(context, key)
            busy = false
            error = when (r) {
                License.Result.Valid -> null
                License.Result.Offline -> License.message("offline")
                is License.Result.Invalid -> License.message(r.reason)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandLogo(size = 72.dp, showText = false)
            Spacer(Modifier.height(20.dp))
            Text(
                if (trialEnded) "Your free trial has ended" else "Activate Lil One",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            if (trialEnded) {
                Text(
                    "Thanks for trying Lil One! Buy it to keep studying. Your PDFs and notes are safe on this device and will all be here.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { openShop(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buy Lil One", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(24.dp))
                Text("Already bought it?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                "Enter the license key from your purchase. You'll find it in your email and in your SupportKori library. One key works on 2 devices.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.uppercase(); error = null },
                label = { Text("License key") },
                placeholder = { Text("XXXXX-XXXXX-XXXXX-XXXXX") },
                leadingIcon = { Icon(Icons.Outlined.Key, null) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { activate() }),
                modifier = Modifier.fillMaxWidth(),
            )
            shown?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { activate() }, enabled = key.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Activate", fontWeight = FontWeight.SemiBold)
            }
            if (!trialEnded) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { openShop(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buy Lil One")
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Developed by Masud Parvej Sohag · Need help? ${License.SUPPORT_EMAIL}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Outlined.Close, "Back to Lil One")
            }
        }
    }
}

/** Opens the SupportKori page where Lil One is sold. */
fun openShop(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(License.SHOP_URL))) }
}
