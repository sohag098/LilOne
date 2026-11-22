package app.lilone.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand palette, taken from the Lil One logo
val Wine = Color(0xFF6E2A31)
val WineDeep = Color(0xFF3F1419)
val WineBright = Color(0xFF6E2A31)
val Cream = Color(0xFFFBF5EC)
val CreamDeep = Color(0xFFEFE3D3)
val Blush = Color(0xFFE9B9C1)

/** The logo's burgundy gradient, for hero cards and the brand header. */
val WineGradient = Brush.linearGradient(listOf(WineBright, WineDeep))

private val LightColors = lightColorScheme(
    primary = Wine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E1E4),
    onPrimaryContainer = Color(0xFF3B0D16),
    secondary = Color(0xFFB0596B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF9E4E7),
    onSecondaryContainer = Color(0xFF40121C),
    tertiary = Color(0xFF9A6B3C),
    background = Color(0xFFFBF7F2),
    onBackground = Color(0xFF221A1A),
    surface = Color(0xFFFBF7F2),
    onSurface = Color(0xFF221A1A),
    surfaceVariant = Color(0xFFF0E7E2),
    onSurfaceVariant = Color(0xFF5E5053),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F1EB),
    surfaceContainer = Color(0xFFF2EBE4),
    surfaceContainerHigh = Color(0xFFECE4DD),
    surfaceContainerHighest = Color(0xFFE6DDD5),
    outline = Color(0xFFCDBFBF),
    outlineVariant = Color(0xFFE5D9D6),
    inverseSurface = Color(0xFF3A2E2E),
    inverseOnSurface = Color(0xFFFBEDEA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0B6BF),
    onPrimary = Color(0xFF4A1520),
    primaryContainer = Color(0xFF6B2533),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFE7BCC5),
    onSecondary = Color(0xFF45202A),
    secondaryContainer = Color(0xFF5C2B36),
    onSecondaryContainer = Color(0xFFFFD9E0),
    tertiary = Color(0xFFE5BF93),
    background = Color(0xFF17110F),
    onBackground = Color(0xFFEEE0DD),
    surface = Color(0xFF17110F),
    onSurface = Color(0xFFEEE0DD),
    surfaceVariant = Color(0xFF2C2322),
    onSurfaceVariant = Color(0xFFD5C3C3),
    surfaceContainerLowest = Color(0xFF110C0B),
    surfaceContainerLow = Color(0xFF1F1816),
    surfaceContainer = Color(0xFF241C1A),
    surfaceContainerHigh = Color(0xFF2E2523),
    surfaceContainerHighest = Color(0xFF392F2D),
    outline = Color(0xFF9C8A8B),
    outlineVariant = Color(0xFF4D3F40),
    inverseSurface = Color(0xFFEEE0DD),
    inverseOnSurface = Color(0xFF2E2523),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

enum class ThemeMode(val label: String) { System("Auto"), Light("Light"), Dark("Dark") }

/** The user's Light / Dark / Auto choice for the whole app (the reader also has its own Night/Sepia). */
object AppTheme {
    private const val PREFS = "settings"
    var mode by mutableStateOf(ThemeMode.System); private set

    fun load(c: Context) {
        val v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", null)
        mode = ThemeMode.entries.firstOrNull { it.name == v } ?: ThemeMode.System
    }

    fun set(c: Context, m: ThemeMode) {
        mode = m
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", m.name).apply()
    }

    @Composable
    fun isDark(): Boolean = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

@Composable
fun LilOneTheme(dark: Boolean = AppTheme.isDark(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
