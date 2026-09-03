package me.ash.reader.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt

enum class OrigReadMotionDirection {
    Backward,
    Forward,
}

/**
 * OrigRead only owns scene geometry here. Timing, easing and spring behavior come from Material 3
 * [androidx.compose.material3.MotionScheme] through [MaterialTheme.motionScheme].
 */
internal object OrigReadMotionGeometry {
    // Primary navigation must be obvious enough to establish hierarchy. The former 18% / 6%
    // travel was technically animated but looked almost like a hard cut on a phone-sized screen.
    const val NavigationForegroundFraction = 1f
    const val NavigationBackgroundFraction = 0.42f
    const val NavigationDepthScale = 0.82f

    // Same-level state changes are intentionally less dramatic than page navigation.
    const val FadeThroughScale = 0.90f
    const val VerticalVisibilityFraction = 0.28f

    const val PressedScale = 0.97f
    const val PressedAlpha = 0.98f

    fun foregroundOffset(fullSize: Int): Int =
        (fullSize * NavigationForegroundFraction).roundToInt()

    fun backgroundOffset(fullSize: Int): Int =
        (fullSize * NavigationBackgroundFraction).roundToInt()

    fun verticalOffset(fullSize: Int): Int = (fullSize * VerticalVisibilityFraction).roundToInt()

}

/** Foreground destination entering on a forward navigation. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun origReadPushEnter(motionScheme: MotionScheme): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { OrigReadMotionGeometry.foregroundOffset(it) },
        animationSpec = motionScheme.defaultSpatialSpec(),
    )

/** Previous destination becoming the background layer during a forward navigation. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun origReadPushExit(motionScheme: MotionScheme): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { -OrigReadMotionGeometry.backgroundOffset(it) },
        animationSpec = motionScheme.defaultSpatialSpec(),
    ) +
        scaleOut(
            targetScale = OrigReadMotionGeometry.NavigationDepthScale,
            animationSpec = motionScheme.defaultSpatialSpec(),
        ) +
        fadeOut(animationSpec = motionScheme.fastEffectsSpec())

/** Background destination returning to the foreground during back navigation. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun origReadPopEnter(motionScheme: MotionScheme): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { -OrigReadMotionGeometry.backgroundOffset(it) },
        animationSpec = motionScheme.defaultSpatialSpec(),
    ) +
        scaleIn(
            initialScale = OrigReadMotionGeometry.NavigationDepthScale,
            animationSpec = motionScheme.defaultSpatialSpec(),
        ) +
        fadeIn(animationSpec = motionScheme.defaultEffectsSpec())

/** Current foreground destination leaving completely on back navigation. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun origReadPopExit(motionScheme: MotionScheme): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { OrigReadMotionGeometry.foregroundOffset(it) },
        animationSpec = motionScheme.defaultSpatialSpec(),
    )

/** Primary page push/pop pattern. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun origReadNavigationTransform(direction: OrigReadMotionDirection): ContentTransform =
    origReadNavigationTransform(direction, MaterialTheme.motionScheme)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun origReadNavigationTransform(
    direction: OrigReadMotionDirection,
    motionScheme: MotionScheme,
): ContentTransform =
    when (direction) {
        OrigReadMotionDirection.Forward ->
            origReadPushEnter(motionScheme) togetherWith origReadPushExit(motionScheme)
        OrigReadMotionDirection.Backward ->
            origReadPopEnter(motionScheme) togetherWith origReadPopExit(motionScheme)
    }

/** Same-level content replacement where spatial direction would imply a false hierarchy. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun origReadFadeThroughTransform(): ContentTransform =
    (fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()) +
        scaleIn(
            initialScale = OrigReadMotionGeometry.FadeThroughScale,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        )) togetherWith
        (fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
            scaleOut(
                targetScale = OrigReadMotionGeometry.FadeThroughScale,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ))

/** Small UI entering the current layout: toolbar, status row, quick actions, etc. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun origReadVisibilityEnter(): EnterTransition =
    slideInVertically(
        initialOffsetY = { OrigReadMotionGeometry.verticalOffset(it) },
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    ) + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec())

/** Counterpart of [origReadVisibilityEnter]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun origReadVisibilityExit(): ExitTransition =
    slideOutVertically(
        targetOffsetY = { OrigReadMotionGeometry.verticalOffset(it) },
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())

/** Shared bounds for text that changes typography/layout between two navigation scenes. */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Modifier.origReadSharedTextBounds(
    key: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
): Modifier =
    with(sharedTransitionScope) {
        this@origReadSharedTextBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
            enter = fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }

/**
 * Adds lightweight pressed feedback to an existing clickable surface. The caller must pass the
 * same [interactionSource] used by clickable/selectable so this modifier does not add a second
 * gesture layer or change semantics.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Modifier.origReadPressFeedback(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val targetScale = if (enabled && pressed) OrigReadMotionGeometry.PressedScale else 1f
    val targetAlpha = if (enabled && pressed) OrigReadMotionGeometry.PressedAlpha else 1f
    val scale by
        animateFloatAsState(
            targetValue = targetScale,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "origread_press_scale",
        )
    val alpha by
        animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "origread_press_alpha",
        )

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}
