package me.ash.reader.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

enum class Direction {
    Backward, Forward
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun sharedYAxisTransitionExpressive(direction: Direction): ContentTransform {
    val direction = when (direction) {
        Direction.Backward -> -1
        Direction.Forward -> 1
    }
    return (slideInVertically(
        initialOffsetY = { (it / 2 * direction).toInt() },
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
    ) + fadeIn(
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
    )) togetherWith (slideOutVertically(
        targetOffsetY = { (it / -4 * direction).toInt() },
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec()

    ) + fadeOut(
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
    ))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun sharedYAxisTransitionSlow(direction: Direction, motionScheme: MotionScheme): ContentTransform {
    val direction = when (direction) {
        Direction.Backward -> -1
        Direction.Forward -> 1
    }
    return (slideInVertically(
        initialOffsetY = { (it / 2 * direction).toInt() },
        animationSpec = motionScheme.slowSpatialSpec()
    ) + fadeIn(
        animationSpec = motionScheme.slowEffectsSpec()
    )) togetherWith (slideOutVertically(
        targetOffsetY = { (it / -2 * direction).toInt() },
        animationSpec = motionScheme.slowSpatialSpec()

    ) + fadeOut(
        animationSpec = motionScheme.fastEffectsSpec()
    ))
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun sharedXAxisTransitionSlow(direction: Direction): ContentTransform {
    val direction = when (direction) {
        Direction.Backward -> -1
        Direction.Forward -> 1
    }
    return (slideInHorizontally(
        initialOffsetX = { (it * 0.1f * direction).toInt() },
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec()
    ) + fadeIn(
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec()
    )) togetherWith (slideOutHorizontally(
        targetOffsetX = { (it * -0.1f * direction).toInt() },
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec()
    ) + fadeOut(
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
    ))
}