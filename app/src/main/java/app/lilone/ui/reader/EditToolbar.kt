package app.lilone.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val EraserIcon: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(15.5f, 3.5f); lineTo(20.5f, 8.5f); lineTo(10.5f, 18.5f); lineTo(5.5f, 18.5f); lineTo(3f, 16f); close()
            moveTo(8f, 11f); lineTo(13f, 16f)
            moveTo(12.5f, 20.5f); lineTo(20.5f, 20.5f)
        }
    }.build()
}

fun Tool.icon(): ImageVector = when (this) {
    Tool.Select -> Icons.Outlined.PanTool
    Tool.AskAI -> Icons.Outlined.AutoAwesome
    Tool.Highlight -> Icons.Outlined.BorderColor
    Tool.Underline -> Icons.Outlined.FormatUnderlined
    Tool.Strike -> Icons.Outlined.FormatStrikethrough
    Tool.Pen -> Icons.Outlined.Create
    Tool.Marker -> Icons.Outlined.Brush
    Tool.Text -> Icons.Outlined.TextFields
    Tool.Note -> Icons.Outlined.StickyNote2
    Tool.Box -> Icons.Outlined.CropSquare
    Tool.Arrow -> Icons.Outlined.NorthEast
    Tool.Eraser -> EraserIcon
}

@Composable
fun EditToolbar(vm: ReaderViewModel, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(vm.tool.hasColor || vm.tool.hasSize) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (vm.tool.hasColor) {
                        Palette.forEach { c ->
                            ColorDot(c, selected = vm.colorOf(vm.tool) == c) { vm.setColor(c) }
                        }
                    }
                    if (vm.tool.hasSize) {
                        if (vm.tool.hasColor) Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        (0..2).forEach { s ->
                            SizeDot(vm, s)
                        }
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tool.entries.forEach { t -> ToolButton(vm, t) }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
                SmallButton(Icons.AutoMirrored.Outlined.Undo, "Undo", enabled = vm.canUndo) { vm.undo() }
                SmallButton(Icons.AutoMirrored.Outlined.Redo, "Redo", enabled = vm.canRedo) { vm.redo() }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
                StylusToggle(vm)
            }
        }
    }
}

@Composable
private fun ToolButton(vm: ReaderViewModel, t: Tool) {
    val selected = vm.tool == t
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .clickable { vm.tool = t }
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .width(50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(t.icon(), contentDescription = t.label, tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        if (t.hasColor) {
            Box(Modifier.size(width = 16.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(Color(vm.colorOf(t))))
        } else Spacer(Modifier.height(3.dp))
        Text(t.label, fontSize = 9.sp, maxLines = 1, color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
    }
}

@Composable
private fun ColorDot(c: Int, selected: Boolean, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(2.5.dp, ring, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(if (selected) 5.dp else 3.dp)
            .clip(CircleShape)
            .background(Color(c))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
    )
}

@Composable
private fun SizeDot(vm: ReaderViewModel, s: Int) {
    val selected = vm.sizeOf(vm.tool) == s
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .clickable { vm.setSize(s) },
        contentAlignment = Alignment.Center,
    ) {
        if (vm.tool == Tool.Text) {
            Text("A", fontSize = listOf(11, 15, 20)[s].sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
        } else {
            val d = listOf(5, 9, 14)[s].dp
            Box(Modifier.size(d).clip(CircleShape).background(scheme.onSurface))
        }
    }
}

@Composable
private fun SmallButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.3f))
    }
}

@Composable
private fun StylusToggle(vm: ReaderViewModel) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { vm.stylusOnly = !vm.stylusOnly }
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (vm.stylusOnly) Icons.Outlined.Gesture else Icons.Outlined.TouchApp,
            contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(if (vm.stylusOnly) "Pen only" else "Finger + pen", fontSize = 9.sp, maxLines = 1, color = scheme.onSurfaceVariant)
    }
}
