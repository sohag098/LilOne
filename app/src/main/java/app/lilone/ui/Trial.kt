package app.lilone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lilone.data.License

/** "Free trial · 20 h left" in the library header while the trial runs. Tapping it offers Buy or "I have a key". */
@Composable
fun TrialChip(modifier: Modifier = Modifier) {
    val left = License.trialLeftText() ?: return
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text("Free trial · $left") },
        leadingIcon = { Icon(Icons.Outlined.Timer, null, Modifier.size(16.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = null,
        modifier = modifier,
    )
    if (open) TrialDialog(left, onDismiss = { open = false })
}

@Composable
fun TrialDialog(left: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Timer, null) },
        title = { Text("Free trial · $left") },
        text = {
            Column {
                Text("Everything in Lil One is unlocked for your 1-day trial. After that, buy it once to keep using it.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your PDFs and notes stay on this device either way.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = { openShop(context); onDismiss() }) {
                Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buy Lil One", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = { License.showActivation = true; onDismiss() }) {
                Icon(Icons.Outlined.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("I have a key")
            }
        },
    )
}
