package app.lilone.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Right-edge fast scroller: drag the thumb to fly to the top, the bottom or anywhere between.
 * Shows while the page is scrolling (or the top bar is up) and fades away after a moment.
 */
@Composable
fun QuickScroller(
    state: LazyListState,
    pageCount: Int,
    showWithChrome: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress

    LaunchedEffect(scrolling, dragging, showWithChrome) {
        if (scrolling || dragging || showWithChrome) visible = true
        else { delay(1600); visible = false }
    }

    // How far down the document we are, 0 = top, 1 = bottom.
    val listFraction by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0f
            if (!state.canScrollForward) return@derivedStateOf 1f
            val pos = state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / first.size.coerceAtLeast(1).toFloat()
            (pos / maxPosition(state)).coerceIn(0f, 1f)
        }
    }
    val fraction = if (dragging) dragFraction else listFraction

    AnimatedVisibility(visible && pageCount > 1, modifier, enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxHeight().width(160.dp)) {
            val density = LocalDensity.current
            val thumbH = 52.dp
            val travelPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
            val thumbY = (fraction * travelPx).roundToInt()

            // faint track
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 6.dp, bottom = 6.dp).width(4.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(50))
            )

            Row(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY) }, verticalAlignment = Alignment.CenterVertically) {
                if (dragging) {
                    val page = (state.firstVisibleItemIndex + 1).coerceIn(1, pageCount)
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, shadowElevation = 3.dp) {
                        Text(
                            "$page / $pageCount", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Surface(
                    shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp),
                    color = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .size(width = 44.dp, height = thumbH)
                        .systemGestureExclusion() // so dragging here isn't taken as the "back" swipe
                        .pointerInput(pageCount) {
                            detectVerticalDragGestures(
                                onDragStart = { dragFraction = listFraction; dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, amount ->
                                change.consume()
                                dragFraction = (dragFraction + amount / travelPx).coerceIn(0f, 1f)
                                val f = dragFraction
                                scope.launch { scrollToFraction(state, f) }
                            }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.UnfoldMore, "Quick scroll",
                            tint = if (dragging) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** Scroll position (in "pages") at the very bottom of the document. */
private fun maxPosition(state: LazyListState): Float {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val sizes = info.visibleItemsInfo.map { it.size }
    val avg = if (sizes.isEmpty()) 1f else sizes.average().toFloat().coerceAtLeast(1f)
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    return (total - viewport / avg).coerceAtLeast(0.001f)
}

private suspend fun scrollToFraction(state: LazyListState, f: Float) {
    val total = state.layoutInfo.totalItemsCount
    if (total == 0) return
    if (f >= 0.999f) { state.scrollToItem(total - 1, Int.MAX_VALUE / 2); return }
    val pos = f * maxPosition(state)
    val index = floor(pos).toInt().coerceIn(0, total - 1)
    val size = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
    state.scrollToItem(index, ((pos - index) * size).roundToInt())
}
