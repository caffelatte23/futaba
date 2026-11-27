package io.github.caffelatte23.futaba.feature.labels

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.zIndex
import kotlin.math.abs

internal class LabelReorderState {
    var ids by mutableStateOf<List<String>?>(null)
        private set

    var draggedId by mutableStateOf<String?>(null)
        private set

    private var center by mutableFloatStateOf(0f)

    fun start(
        id: String,
        order: List<String>,
        layout: LazyListLayoutInfo,
    ) {
        val item = layout.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        ids = order
        center = item.offset + item.size / 2f
        draggedId = id
    }

    fun move(
        delta: Float,
        layout: LazyListLayoutInfo,
    ) {
        val order = ids.orEmpty()
        center = (center + delta).coerceIn(layout.viewportStartOffset.toFloat(), layout.viewportEndOffset.toFloat())
        val from = order.indexOf(draggedId)
        layout.visibleItemsInfo
            .filter { it.key in order }
            .minByOrNull { abs(center - (it.offset + it.size / 2f)) }
            ?.let { target ->
                val to = order.indexOf(target.key)
                if (from >= 0 && from != to) ids = order.toMutableList().apply { add(to, removeAt(from)) }
            }
    }

    fun offset(layout: LazyListLayoutInfo): Float {
        val item = layout.visibleItemsInfo.firstOrNull { it.key == draggedId } ?: return 0f
        return center - (item.offset + item.size / 2f)
    }

    fun edgeScroll(
        layout: LazyListLayoutInfo,
        edge: Float,
        maxStep: Float,
    ): Float {
        val nearTop = layout.viewportStartOffset + edge - center
        val nearBottom = center - (layout.viewportEndOffset - edge)
        return ((nearBottom.coerceAtLeast(0f) - nearTop.coerceAtLeast(0f)) / edge)
            .coerceIn(-1f, 1f) * maxStep
    }

    fun cancel() {
        draggedId = null
        ids = null
    }
}

internal fun Modifier.reorderHandle(
    id: String,
    drag: LabelReorderState,
    list: LazyListState,
    sourceOrder: State<List<String>>,
    onReorder: State<(List<String>) -> Unit>,
): Modifier =
    pointerInput(id, drag, list) {
        try {
            detectVerticalDragGestures(
                onDragStart = { drag.start(id, sourceOrder.value, list.layoutInfo) },
                onDragCancel = drag::cancel,
                onDragEnd = {
                    val result = drag.ids
                    val current = sourceOrder.value
                    if (result != null && result != current &&
                        result.toSet() == current.toSet()
                    ) {
                        onReorder.value(result)
                    }
                    drag.cancel()
                },
                onVerticalDrag = { _, delta -> drag.move(delta, list.layoutInfo) },
            )
        } finally {
            // Lazy items can leave composition while dragging if labels change externally.
            if (drag.draggedId == id) drag.cancel()
        }
    }

internal fun Modifier.offsetDraggedRow(
    id: String,
    drag: LabelReorderState,
    list: LazyListState,
): Modifier =
    if (drag.draggedId == id) {
        zIndex(1f).graphicsLayer { translationY = drag.offset(list.layoutInfo) }
    } else {
        this
    }

internal fun moveActions(
    index: Int,
    order: List<String>,
    up: String,
    down: String,
    onMove: (List<String>) -> Unit,
): List<CustomAccessibilityAction> =
    buildList {
        if (index > 0) add(moveAction(up, order, index, index - 1, onMove))
        if (index < order.lastIndex) add(moveAction(down, order, index, index + 1, onMove))
    }

private fun moveAction(
    title: String,
    order: List<String>,
    from: Int,
    to: Int,
    onMove: (List<String>) -> Unit,
) = CustomAccessibilityAction(title) {
    onMove(order.toMutableList().apply { add(to, removeAt(from)) })
    true
}
