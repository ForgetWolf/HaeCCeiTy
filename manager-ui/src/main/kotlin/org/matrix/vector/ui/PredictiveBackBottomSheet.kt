package org.matrix.vector.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Shared template for manager bottom sheets: downward predictive-back preview, cancellation
 * restores the panel, and committed Back continues the same translation until it is off-screen.
 * Material still owns opening, dragging, scrolling and the half/fully-expanded anchors.
 *
 * Put panel contents in [content]; use [modifier] for panel-specific size constraints. Keep Back
 * handling here so every panel uses the same motion and scrim instead of drifting independently.
 */
@Composable
fun PredictiveBackBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val finishSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val restoreSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    var sheetHeight by remember { mutableIntStateOf(0) }
    var slideDistance by remember { mutableFloatStateOf(0f) }
    var gestureActive by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var gestureId by remember { mutableIntStateOf(0) }
    var restoreJob by remember { mutableStateOf<Job?>(null) }
    val scrim = BottomSheetDefaults.ScrimColor

    ModalBottomSheet(
        onDismissRequest = { if (!dismissing) currentDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !gestureActive && !dismissing,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = false,
            shouldDismissOnClickOutside = !gestureActive && !dismissing,
        ),
        modifier = modifier.onSizeChanged { sheetHeight = it.height }.graphicsLayer {
            translationY = slideDistance * slide.value.coerceIn(0f, 1f)
        },
        scrimColor = scrim.copy(alpha = scrim.alpha * (1f - slide.value.coerceIn(0f, 1f))),
    ) {
        // Read the dialog's window, including its system-bar area, rather than the activity's.
        val windowHeight = LocalWindowInfo.current.containerSize.height
        // Register inside the dialog so it owns Back instead of the activity behind it.
        PredictiveBackHandler(enabled = !dismissing && sheetState.isVisible) { events ->
            if (dismissing) {
                events.collect { }
                return@PredictiveBackHandler
            }
            val id = ++gestureId
            restoreJob?.cancel()
            val startSlide = slide.value.coerceIn(0f, BackPreviewFraction)
            // A partially expanded sheet is taller than its visible portion. Move only that
            // visible portion so the panel and scrim finish disappearing together.
            slideDistance = (windowHeight - sheetState.requireOffset())
                .coerceIn(0f, sheetHeight.toFloat())
            gestureActive = true
            try {
                events.collect { event ->
                    // Material's progress easing, with a bounded preview that stays visible
                    // until Back is confirmed. snapTo keeps every update tied to the finger.
                    val progress = BackPreviewEasing.transform(event.progress.coerceIn(0f, 1f))
                    slide.snapTo(startSlide + (BackPreviewFraction - startSlide) * progress)
                }
                dismissing = true
                // Use the same transform for the exit: calling sheetState.hide() here would
                // introduce a second offset and jump when handing off from the gesture.
                scope.launch {
                    slide.animateTo(1f, finishSpec)
                    currentDismiss()
                }
            } catch (cancelled: CancellationException) {
                if (id == gestureId && !dismissing) {
                    // Gesture cancellation can cancel this handler's coroutine. Restore in the
                    // sheet's scope; the next gesture cancels that restoration before taking over.
                    restoreJob = scope.launch { slide.animateTo(0f, restoreSpec) }
                }
                throw cancelled
            } finally {
                if (id == gestureId) gestureActive = false
            }
        }
        content()
    }
}

// Matches Material 3's predictive-back easing; only the visual mapping changes.
private val BackPreviewEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
private const val BackPreviewFraction = 0.35f
