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
    const val NavigationIncomingFraction = 0.18f
    const val NavigationOutgoingFraction = 0.06f
    const val NavigationIncomingScale = 0.985f
    const val NavigationOutgoingScale = 0.975f
    const val VerticalVisibilityFraction = 0.12f
    const val PressedScale = 0.985f
    const val PressedAlpha = 0.94f

    fun incomingOffset(
        fullSize: Int,
        direction: OrigReadMotionDirection,
    ): Int = (fullSize * NavigationIncomingFraction * direction.sign).roundToInt()

    fun outgoingOffset(
        fullSize: Int,
        direction: OrigReadMotionDirection,
    ): Int = (fullSize * -NavigationOutgoingFraction * direction.sign).roundToInt()

    fun verticalOffset(fullSize: Int): Int = (fullSize * VerticalVisibilityFraction).roundToInt()

    private val OrigReadMotionDirection.sign: Int
        get() =
            when (this) {
                OrigReadMotionDirection.Backward -> -1
                OrigReadMotionDirection.Forward -> 1
            }
}

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
    (slideInHorizontally(
        initialOffsetX = { OrigReadMotionGeometry.incomingOffset(it, direction) },
        animationSpec = motionScheme.defaultSpatialSpec(),
    ) +
        fadeIn(animationSpec = motionScheme.defaultEffectsSpec()) +
        scaleIn(
            initialScale = OrigReadMotionGeometry.NavigationIncomingScale,
            animationSpec = motionScheme.defaultSpatialSpec(),
        )) togetherWith
        (slideOutHorizontally(
            targetOffsetX = { OrigReadMotionGeometry.outgoingOffset(it, direction) },
            animationSpec = motionScheme.defaultSpatialSpec(),
        ) +
            fadeOut(animationSpec = motionScheme.fastEffectsSpec()) +
            scaleOut(
                targetScale = OrigReadMotionGeometry.NavigationOutgoingScale,
                animationSpec = motionScheme.defaultSpatialSpec(),
            ))

/** Same-level content replacement where spatial direction would imply a false hierarchy. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun origReadFadeThroughTransform(): ContentTransform =
    (fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()) +
        scaleIn(
            initialScale = OrigReadMotionGeometry.NavigationIncomingScale,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        )) togetherWith
        (fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
            scaleOut(
                targetScale = OrigReadMotionGeometry.NavigationOutgoingScale,
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
