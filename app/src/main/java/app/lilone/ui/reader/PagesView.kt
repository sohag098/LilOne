package app.lilone.ui.reader

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.lilone.pdf.PageBox
import com.artifex.mupdf.fitz.Quad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Inverts the page but rotates hues back, so highlights and diagrams keep their colours. */
private val NightFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f * 0.84f, -1.43f * 0.84f, -0.144f * 0.84f, 0f, 242f,
            -0.426f * 0.84f, -0.43f * 0.84f, -0.144f * 0.84f, 0f, 242f,
            -0.426f * 0.84f, -1.43f * 0.84f, 0.856f * 0.84f, 0f, 242f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

private val SepiaFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.98f, 0f, 0f, 0f, 0f,
            0f, 0.93f, 0f, 0f, 0f,
            0f, 0f, 0.80f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

fun pageFilter(mode: ReadMode): ColorFilter? = when (mode) {
    ReadMode.Day -> null
    ReadMode.Sepia -> SepiaFilter
    ReadMode.Night -> NightFilter
}

fun pagePaper(mode: ReadMode) = when (mode) {
    ReadMode.Day -> Color.White
    ReadMode.Sepia -> Color(0xFFF9EDD6)
    ReadMode.Night -> Color(0xFF1C1C1C)
}

@Composable
fun PagesView(
    vm: ReaderViewModel,
    listState: LazyListState,
    topPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tapState = remember { TapState() }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val padPx = with(density) { 12.dp.toPx() }
        val pageW = (containerW - 2 * padPx).coerceAtLeast(100f)
        val touchPx = with(density) { 14.dp.toPx() }
        val doubleTapSlop = with(density) { 48.dp.toPx() }

        // Re-render sharper when zoomed in, in coarse steps so pinching doesn't re-render every frame.
        val quality = when {
            vm.zoom < 1.4f -> 1f
            vm.zoom < 2.6f -> 2f
            else -> 2.6f
        }
        val renderW = (pageW * quality).toInt().coerceAtMost(3400)

        fun radiusPt(page: Int): Float {
            val box = vm.boxes.getOrNull(page) ?: return 6f
            return touchPx * box.w / pageW / vm.zoom
        }

        /** Maps a point in this box to (page, point in PDF coordinates). */
        fun hitFor(pos: Offset, lockPage: Int = -1): Pair<Int, PointF>? {
            val info = listState.layoutInfo
            val lx = (pos.x - vm.offsetX) / vm.zoom
            val ly = pos.y / vm.zoom
            val item = info.visibleItemsInfo.firstOrNull {
                if (lockPage >= 0) it.index == lockPage
                else {
                    val top = it.offset - info.viewportStartOffset
                    ly >= top && ly < top + it.size
                }
            } ?: return null
            val box = vm.boxes.getOrNull(item.index) ?: return null
            val top = item.offset - info.viewportStartOffset
            val k = box.w / pageW
            val x = box.x0 + (lx - padPx) * k
            val y = box.y0 + (ly - top) * k
            return item.index to PointF(x.coerceIn(box.x0, box.x0 + box.w), y.coerceIn(box.y0, box.y0 + box.h))
        }

        fun applyZoom(change: Float, centroid: Offset, pan: Offset) {
            val old = vm.zoom
            val new = (old * change).coerceIn(1f, 5f)
            val lx = (centroid.x - vm.offsetX) / old
            vm.offsetX = (centroid.x - lx * new + pan.x).coerceIn(containerW - containerW * new, 0f)
            val ly = centroid.y / old
            listState.dispatchRawDelta(ly - centroid.y / new - pan.y / new)
            vm.updateZoom(new)
        }

        fun handleTap(pos: Offset) {
            val hit = hitFor(pos) ?: return
            if (vm.editMode && (vm.tool == Tool.Text || vm.tool == Tool.Note)) {
                vm.tap(hit.first, hit.second, radiusPt(hit.first))
                return
            }
            val now = System.currentTimeMillis()
            val pending = tapState.pending
            if (pending != null && pending.isActive && now - tapState.time < 300 &&
                (pos - tapState.pos).getDistance() < doubleTapSlop
            ) {
                pending.cancel()
                tapState.pending = null
                // double tap: zoom in on that spot, or back out
                if (vm.zoom > 1.05f) applyZoom(1f / vm.zoom, pos, Offset.Zero) else applyZoom(2.2f, pos, Offset.Zero)
                return
            }
            tapState.time = now
            tapState.pos = pos
            tapState.pending = scope.launch {
                delay(260)
                vm.tap(hit.first, hit.second, radiusPt(hit.first))
            }
        }

        val extraBottom = if (vm.zoom > 1.01f) with(density) { (containerH * 0.8f).toDp() } else 0.dp

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPadding + 8.dp, bottom = 140.dp + extraBottom),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(containerW, pageW) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val isStylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                        if (isStylus) vm.onStylusSeen()
                        val eraserTip = down.type == PointerType.Eraser
                        val canDraw = vm.editMode && (vm.tool.drags || eraserTip) && (isStylus || !vm.stylusOnly)
                        var mode = 0 // 0 = scroll/tap, 1 = drawing, 2 = pinch, 3 = moving typed text / a note
                        var lockPage = -1
                        if (canDraw) {
                            val start = hitFor(down.position)
                            if (start != null) {
                                mode = 1
                                lockPage = start.first
                                down.consume()
                                vm.toolDown(start.first, start.second, eraserTip, radiusPt(start.first))
                            }
                        } else if (vm.editMode && !vm.tool.drags && !eraserTip) {
                            // Select / Text / Note: press on typed text or a note and drag to move it
                            val start = hitFor(down.position)
                            val target = start?.let { vm.movableAt(it.first, it.second, radiusPt(it.first)) }
                            if (start != null && target != null) {
                                mode = 3
                                lockPage = start.first
                                down.consume()
                                vm.moveStart(target, start.second)
                            }
                        }
                        var moved = false
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed >= 2) {
                                if (mode == 1) vm.toolCancel()
                                if (mode == 3) vm.moveCancel()
                                mode = 2
                                val c = ev.calculateCentroid(useCurrent = true)
                                if (c != Offset.Unspecified) applyZoom(ev.calculateZoom(), c, ev.calculatePan())
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.firstOrNull() ?: break
                            if (pressed == 0) {
                                if (mode == 1) {
                                    val h = hitFor(ch.position, lockPage)
                                    if (h != null) vm.toolUp(h.second, radiusPt(lockPage)) else vm.toolCancel()
                                    ch.consume()
                                } else if (mode == 3) {
                                    ch.consume()
                                    if (!vm.moveEnd()) handleTap(down.position)
                                } else if (mode == 0 && !moved && ch.uptimeMillis - down.uptimeMillis < 450) {
                                    handleTap(down.position)
                                }
                                break
                            }
                            if ((ch.position - down.position).getDistance() > slop) moved = true
                            when (mode) {
                                1 -> {
                                    hitFor(ch.position, lockPage)?.let { vm.toolMove(it.second, radiusPt(lockPage)) }
                                    ch.consume()
                                }
                                2 -> ev.changes.forEach { it.consume() }
                                3 -> {
                                    hitFor(ch.position, lockPage)?.let { vm.moveTo(it.second) }
                                    ch.consume()
                                }
                                else -> if (vm.zoom > 1.01f && moved) {
                                    vm.offsetX = (vm.offsetX + ch.positionChange().x)
                                        .coerceIn(containerW - containerW * vm.zoom, 0f)
                                }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = vm.zoom
                    scaleY = vm.zoom
                    translationX = vm.offsetX
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            itemsIndexed(vm.boxes, key = { i, _ -> i }) { i, box ->
                PageItem(vm, i, box, pageW, renderW)
            }
        }
    }
}

private class TapState {
    var pending: Job? = null
    var time = 0L
    var pos = Offset.Zero
}

@Composable
private fun PageItem(vm: ReaderViewModel, index: Int, box: PageBox, pageWpx: Float, renderW: Int) {
    val density = LocalDensity.current
    val wDp = with(density) { pageWpx.toDp() }
    val hDp = with(density) { (pageWpx * box.h / box.w).toDp() }
    val version = vm.version(index)
    val bitmap by produceState(initialValue = vm.cached(index), index, version, renderW) {
        vm.render(index, renderW)?.let { value = it }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(wDp, hDp)
                .shadow(3.dp, RoundedCornerShape(3.dp))
                .background(pagePaper(vm.readMode))
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = pageFilter(vm.readMode),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Canvas(Modifier.fillMaxSize()) { drawOverlays(vm, index, box) }
        }
    }
}

private fun DrawScope.drawOverlays(vm: ReaderViewModel, index: Int, box: PageBox) {
    val k = size.width / box.w
    fun px(x: Float, y: Float) = Offset((x - box.x0) * k, (y - box.y0) * k)
    fun quadPath(q: Quad) = Path().apply {
        val a = px(q.ul_x, q.ul_y); val b = px(q.ur_x, q.ur_y); val c = px(q.lr_x, q.lr_y); val d = px(q.ll_x, q.ll_y)
        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
    }

    // search results on this page, the active one stronger
    vm.searchResults.forEach { hit ->
        if (hit.page == index) {
            val active = hit == vm.activeHit
            hit.quads.forEach { q -> drawPath(quadPath(q), Color(0xFFFF922B).copy(alpha = if (active) 0.45f else 0.18f)) }
        }
    }

    if (vm.selPage == index) {
        val c = Color(vm.colorOf(vm.activeTool))
        vm.selQuads.forEach { q -> drawPath(quadPath(q), c.copy(alpha = 0.4f)) }
    }

    // typed text under a Highlight / Underline / Strike drag
    if (vm.markPage == index) vm.markRect?.let { r ->
        drawRect(
            Color(vm.colorOf(vm.activeTool)).copy(alpha = 0.35f),
            topLeft = px(r.left, r.top), size = androidx.compose.ui.geometry.Size(r.width() * k, r.height() * k),
        )
    }

    // typed text or a note being dragged: fade the original, draw it at the finger
    vm.moving?.takeIf { it.page == index }?.let { m ->
        val b = m.bounds
        val sz = androidx.compose.ui.geometry.Size(b.width() * k, b.height() * k)
        val pad = 3.dp.toPx()
        drawRect(pagePaper(vm.readMode).copy(alpha = 0.8f), topLeft = px(b.left, b.top) - Offset(1f, 1f), size = sz.copy(sz.width + 2f, sz.height + 2f))
        val at = px(b.left + vm.moveDx, b.top + vm.moveDy)
        vm.moveGhost?.let { g ->
            drawImage(
                g, dstOffset = androidx.compose.ui.unit.IntOffset(at.x.toInt(), at.y.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(sz.width.toInt().coerceAtLeast(1), sz.height.toInt().coerceAtLeast(1)),
                colorFilter = pageFilter(vm.readMode),
            )
        }
        drawRect(
            Color(0xFF7A2D3A), topLeft = at - Offset(pad, pad), size = sz.copy(sz.width + 2 * pad, sz.height + 2 * pad),
            style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
        )
    }

    if (vm.strokePage == index && vm.stroke.isNotEmpty()) {
        val t = vm.activeTool
        val path = Path()
        vm.stroke.forEachIndexed { n, p ->
            val o = px(p.x, p.y)
            if (n == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        val alpha = if (t == Tool.Marker) 0.38f else 1f
        drawPath(
            path, Color(vm.colorOf(t)).copy(alpha = alpha),
            style = Stroke(width = vm.strokeWidth(t) * k, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    if (vm.shapePage == index) {
        val t = vm.activeTool
        val a = px(vm.shapeA.x, vm.shapeA.y); val b = px(vm.shapeB.x, vm.shapeB.y)
        val color = Color(vm.colorOf(t))
        val w = vm.strokeWidth(t) * k
        val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
        val sz = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
        if (t == Tool.AskAI) {
            val purple = Color(0xFF7A2D3A)
            drawRect(purple.copy(alpha = 0.10f), topLeft, sz)
            drawRect(
                purple, topLeft, sz,
                style = Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
        } else if (t == Tool.Box) {
            drawRect(color, topLeft = topLeft, size = sz, style = Stroke(w))
        } else {
            drawLine(color, a, b, strokeWidth = w, cap = StrokeCap.Round)
            val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x)
            val len = 9f * k + w * 2
            for (s in listOf(-0.5f, 0.5f)) {
                val e = Offset(b.x - len * kotlin.math.cos(ang + s), b.y - len * kotlin.math.sin(ang + s))
                drawLine(color, b, e, strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}
