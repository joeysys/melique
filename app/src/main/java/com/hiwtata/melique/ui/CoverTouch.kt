package com.hiwtata.melique.ui

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class CoverAxis { Horizontal, Vertical }

data class CoverDrag(
    val dx: Float,
    val dy: Float,
    val vx: Float,
    val vy: Float,
    val axis: CoverAxis?,
)

fun Modifier.coverDrag(
    enabled: Boolean,
    onMove: (CoverDrag) -> Unit,
    onSettle: (CoverDrag) -> Unit,
    onTap: () -> Unit,
    onLong: () -> Unit,
): Modifier = composed {
    val moveState = rememberUpdatedState(onMove)
    val settleState = rememberUpdatedState(onSettle)
    val tapState = rememberUpdatedState(onTap)
    val longState = rememberUpdatedState(onLong)
    pointerInput(enabled) {
        val slop = 16.dp.toPx()
        val inputScope = this
        coroutineScope {
            while (true) {
                val down = inputScope.awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                if (!enabled) {
                    inputScope.awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) break
                        }
                    }
                    continue
                }
                var total = Offset.Zero
                var axis: CoverAxis? = null
                var longFired = false
                val tracker = VelocityTracker()
                val longPress = launch {
                    delay(480)
                    if (axis == null && hypot(total.x.toDouble(), total.y.toDouble()) < slop) {
                        longFired = true
                        longState.value()
                    }
                }
                try {
                    inputScope.awaitPointerEventScope {
                        tracker.addPointerInputChange(down)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            tracker.addPointerInputChange(change)
                            if (!change.pressed) break
                            if (longFired) {
                                change.consume()
                                continue
                            }
                            total += change.positionChange()
                            if (axis == null && hypot(total.x.toDouble(), total.y.toDouble()) >= slop) {
                                axis = if (abs(total.x) > abs(total.y)) CoverAxis.Horizontal else CoverAxis.Vertical
                                longPress.cancel()
                            }
                            if (axis != null) {
                                moveState.value(CoverDrag(total.x, total.y, 0f, 0f, axis))
                                change.consume()
                            }
                        }
                    }
                } finally {
                    longPress.cancel()
                }
                if (longFired) continue
                if (axis == null && hypot(total.x.toDouble(), total.y.toDouble()) < slop) {
                    tapState.value()
                } else {
                    val velocity = tracker.calculateVelocity()
                    settleState.value(CoverDrag(total.x, total.y, velocity.x, velocity.y, axis))
                }
            }
        }
    }
}
