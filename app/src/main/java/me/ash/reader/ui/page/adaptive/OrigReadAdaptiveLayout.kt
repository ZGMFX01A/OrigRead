package me.ash.reader.ui.page.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class OrigReadWindowWidthClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge,
}

enum class OrigReadWindowHeightClass {
    Compact,
    Medium,
    Expanded,
}

enum class OrigReadArticleAssistantPresentation {
    BottomSheet,
    SupportingPane,
    TabletopSupportingPane,
}

enum class OrigReadReaderScaffoldMode {
    Standard,
    TwoPaneOnMediumVerticalHinge,
    FoldManagedSinglePane,
}

/** Window 坐标系中的 Fold/Hinge 描述，统一换算为 dp，避免产品层依赖具体设备型号。 */
data class OrigReadFoldFeatureInfo(
    val leftDp: Float,
    val topDp: Float,
    val rightDp: Float,
    val bottomDp: Float,
    val isFlat: Boolean,
    val isVertical: Boolean,
    val isSeparating: Boolean,
    val isOccluding: Boolean,
)

data class OrigReadPaneRegion(
    val leftDp: Float,
    val topDp: Float,
    val rightDp: Float,
    val bottomDp: Float,
) {
    val widthDp: Float
        get() = (rightDp - leftDp).coerceAtLeast(0f)

    val heightDp: Float
        get() = (bottomDp - topDp).coerceAtLeast(0f)

    val area: Float
        get() = widthDp * heightDp

    fun intersect(other: OrigReadPaneRegion): OrigReadPaneRegion? {
        val left = maxOf(leftDp, other.leftDp)
        val top = maxOf(topDp, other.topDp)
        val right = minOf(rightDp, other.rightDp)
        val bottom = minOf(bottomDp, other.bottomDp)
        return OrigReadPaneRegion(left, top, right, bottom)
            .takeIf { it.widthDp > 0f && it.heightDp > 0f }
    }
}

data class OrigReadReaderAssistantRegions(
    val reader: OrigReadPaneRegion,
    val assistant: OrigReadPaneRegion,
)

data class OrigReadFoldLayoutInfo(
    val isTabletop: Boolean = false,
    val hinges: List<OrigReadFoldFeatureInfo> = emptyList(),
) {
    val separatingVerticalHinges: List<OrigReadFoldFeatureInfo>
        get() = hinges.filter { it.isVertical && (it.isSeparating || it.isOccluding) }

    val separatingHorizontalHinges: List<OrigReadFoldFeatureInfo>
        get() = hinges.filter { !it.isVertical && (it.isSeparating || it.isOccluding) }

    val hasSeparatingVerticalHinge: Boolean
        get() = separatingVerticalHinges.isNotEmpty()

    val hasSeparatingHorizontalHinge: Boolean
        get() = separatingHorizontalHinges.isNotEmpty()

    val hasSeparatingHinge: Boolean
        get() = hasSeparatingVerticalHinge || hasSeparatingHorizontalHinge

    val isBookPosture: Boolean
        get() =
            !isTabletop &&
                separatingVerticalHinges.any { !it.isFlat }

    fun horizontalRegions(widthDp: Float, heightDp: Float): List<OrigReadPaneRegion> =
        splitAxisRegions(
            start = 0f,
            end = widthDp,
            blockers = separatingVerticalHinges.map { it.leftDp to it.rightDp },
        ).map { (left, right) ->
            OrigReadPaneRegion(left, 0f, right, heightDp)
        }

    fun verticalRegions(widthDp: Float, heightDp: Float): List<OrigReadPaneRegion> =
        splitAxisRegions(
            start = 0f,
            end = heightDp,
            blockers = separatingHorizontalHinges.map { it.topDp to it.bottomDp },
        ).map { (top, bottom) ->
            OrigReadPaneRegion(0f, top, widthDp, bottom)
        }

    /**
     * 为普通设置/工具页选一块不跨硬 hinge 的连续区域。Tabletop 等面积时优先下半区，
     * 让需要触控/输入的设置项落在更容易操作的一侧。
     */
    fun primarySafeRegion(container: OrigReadPaneRegion): OrigReadPaneRegion {
        val safeRegions = safeRegions(container)
        if (safeRegions.isEmpty()) return container
        return safeRegions.maxWithOrNull(
            compareBy<OrigReadPaneRegion> { it.area }
                .thenBy { if (isTabletop) it.topDp else -it.leftDp }
        ) ?: container
    }

    fun safeRegions(container: OrigReadPaneRegion): List<OrigReadPaneRegion> {
        val xRegions =
            splitAxisRegions(
                start = container.leftDp,
                end = container.rightDp,
                blockers =
                    separatingVerticalHinges.mapNotNull { hinge ->
                        val start = maxOf(container.leftDp, hinge.leftDp)
                        val end = minOf(container.rightDp, hinge.rightDp)
                        (start to end).takeIf { end > start }
                    },
            )
        val yRegions =
            splitAxisRegions(
                start = container.topDp,
                end = container.bottomDp,
                blockers =
                    separatingHorizontalHinges.mapNotNull { hinge ->
                        val start = maxOf(container.topDp, hinge.topDp)
                        val end = minOf(container.bottomDp, hinge.bottomDp)
                        (start to end).takeIf { end > start }
                    },
            )
        return xRegions.flatMap { (left, right) ->
            yRegions.map { (top, bottom) -> OrigReadPaneRegion(left, top, right, bottom) }
        }.filter { it.widthDp > 0f && it.heightDp > 0f }
    }

    companion object {
        val Flat = OrigReadFoldLayoutInfo()
    }
}

private fun splitAxisRegions(
    start: Float,
    end: Float,
    blockers: List<Pair<Float, Float>>,
): List<Pair<Float, Float>> {
    if (end <= start) return emptyList()
    val normalized =
        blockers.mapNotNull { (rawStart, rawEnd) ->
            val clippedStart = maxOf(start, minOf(rawStart, rawEnd))
            val clippedEnd = minOf(end, maxOf(rawStart, rawEnd))
            (clippedStart to clippedEnd).takeIf { clippedEnd > clippedStart }
        }.sortedBy { it.first }
    if (normalized.isEmpty()) return listOf(start to end)

    val merged = mutableListOf<Pair<Float, Float>>()
    normalized.forEach { current ->
        val previous = merged.lastOrNull()
        if (previous != null && current.first <= previous.second) {
            merged[merged.lastIndex] = previous.first to maxOf(previous.second, current.second)
        } else {
            merged += current
        }
    }

    val regions = mutableListOf<Pair<Float, Float>>()
    var cursor = start
    merged.forEach { (blockStart, blockEnd) ->
        if (blockStart > cursor) regions += cursor to blockStart
        cursor = maxOf(cursor, blockEnd)
    }
    if (cursor < end) regions += cursor to end
    return regions
}

/** OrigRead 对当前 app window 的统一产品级能力描述。 */
data class OrigReadAdaptiveLayoutProfile(
    val widthDp: Int,
    val heightDp: Int,
    val widthClass: OrigReadWindowWidthClass,
    val heightClass: OrigReadWindowHeightClass,
    val foldLayoutInfo: OrigReadFoldLayoutInfo = OrigReadFoldLayoutInfo.Flat,
) {
    val usesPhoneLikeFlow: Boolean
        get() =
            heightClass == OrigReadWindowHeightClass.Compact ||
                widthClass == OrigReadWindowWidthClass.Compact ||
                (widthClass == OrigReadWindowWidthClass.Medium &&
                    !foldLayoutInfo.hasSeparatingVerticalHinge)

    val supportsListDetailWorkspace: Boolean
        get() =
            heightClass != OrigReadWindowHeightClass.Compact &&
                widthClass != OrigReadWindowWidthClass.Compact &&
                (widthClass >= OrigReadWindowWidthClass.Expanded ||
                    foldLayoutInfo.hasSeparatingVerticalHinge)

    val supportsThreePaneWorkspace: Boolean
        get() =
            heightClass != OrigReadWindowHeightClass.Compact &&
                !foldLayoutInfo.hasSeparatingHinge &&
                widthDp >= ThreePaneReaderWorkspaceMinWidthDp

    /** Settings 根页在可稳定并排展示两张设置卡片时提高信息密度。 */
    val settingsRootColumnCount: Int
        get() = if (supportsListDetailWorkspace && !foldLayoutInfo.hasSeparatingHinge) 2 else 1

    /**
     * Discovery 的分类区只有在能同时保留约 300dp 分类栏和舒适结果区时才常驻。
     * 840dp 刚进入 Expanded 时仍保留原 BottomSheet，避免为了“大屏”把结果列表挤窄。
     */
    val showsPersistentDiscoveryCategories: Boolean
        get() =
            heightClass != OrigReadWindowHeightClass.Compact &&
                !foldLayoutInfo.hasSeparatingHinge &&
                widthDp >= PersistentDiscoveryCategoriesMinWidthDp
}

// 400dp list + 24dp list/detail spacer + (600dp readable text + 48dp reader padding)
// + 384dp assistant. Pixel Tablet 1280dp was verified to leave only ~424dp readable text when
// forced into three panes, so Large is a candidate class rather than an unconditional three-pane
// decision.
internal const val ThreePaneReaderWorkspaceMinWidthDp = 1456
internal const val PersistentDiscoveryCategoriesMinWidthDp = 1000

internal fun articleAssistantPresentation(
    profile: OrigReadAdaptiveLayoutProfile,
): OrigReadArticleAssistantPresentation =
    when {
        profile.foldLayoutInfo.isTabletop &&
            profile.foldLayoutInfo.hasSeparatingHorizontalHinge ->
            OrigReadArticleAssistantPresentation.TabletopSupportingPane
        profile.supportsListDetailWorkspace -> OrigReadArticleAssistantPresentation.SupportingPane
        else -> OrigReadArticleAssistantPresentation.BottomSheet
    }

internal fun shouldTemporarilyHideListForAssistant(
    profile: OrigReadAdaptiveLayoutProfile,
    assistantPaneVisible: Boolean,
): Boolean =
    assistantPaneVisible &&
        profile.supportsListDetailWorkspace &&
        !profile.foldLayoutInfo.isTabletop &&
        !profile.supportsThreePaneWorkspace

internal fun readerScaffoldMode(
    profile: OrigReadAdaptiveLayoutProfile,
    assistantPaneVisible: Boolean,
): OrigReadReaderScaffoldMode =
    when {
        profile.foldLayoutInfo.isTabletop &&
            profile.foldLayoutInfo.hasSeparatingHorizontalHinge ->
            OrigReadReaderScaffoldMode.FoldManagedSinglePane
        assistantPaneVisible && profile.foldLayoutInfo.hasSeparatingVerticalHinge ->
            OrigReadReaderScaffoldMode.FoldManagedSinglePane
        profile.widthClass == OrigReadWindowWidthClass.Medium &&
            profile.foldLayoutInfo.hasSeparatingVerticalHinge ->
            OrigReadReaderScaffoldMode.TwoPaneOnMediumVerticalHinge
        else -> OrigReadReaderScaffoldMode.Standard
    }

internal fun foldReaderAssistantRegions(
    profile: OrigReadAdaptiveLayoutProfile,
    isRtl: Boolean,
): OrigReadReaderAssistantRegions? {
    val fold = profile.foldLayoutInfo
    if (fold.isTabletop && fold.hasSeparatingHorizontalHinge) {
        val regions = fold.verticalRegions(profile.widthDp.toFloat(), profile.heightDp.toFloat())
        if (regions.size < 2) return null
        return OrigReadReaderAssistantRegions(
            reader = regions.first(),
            assistant = regions.last(),
        )
    }
    if (fold.hasSeparatingVerticalHinge) {
        val regions = fold.horizontalRegions(profile.widthDp.toFloat(), profile.heightDp.toFloat())
        if (regions.size < 2) return null
        return if (isRtl) {
            OrigReadReaderAssistantRegions(reader = regions.last(), assistant = regions.first())
        } else {
            OrigReadReaderAssistantRegions(reader = regions.first(), assistant = regions.last())
        }
    }
    return null
}

internal fun foldPrimaryReaderRegion(
    profile: OrigReadAdaptiveLayoutProfile,
): OrigReadPaneRegion? =
    profile.foldLayoutInfo
        .takeIf { it.isTabletop && it.hasSeparatingHorizontalHinge }
        ?.verticalRegions(profile.widthDp.toFloat(), profile.heightDp.toFloat())
        ?.firstOrNull()

internal fun foldPrimaryInteractiveRegion(
    profile: OrigReadAdaptiveLayoutProfile,
): OrigReadPaneRegion? =
    profile.foldLayoutInfo
        .takeIf { it.isTabletop && it.hasSeparatingHorizontalHinge }
        ?.verticalRegions(profile.widthDp.toFloat(), profile.heightDp.toFloat())
        ?.lastOrNull()

internal fun shouldKeepAssistantVisibleForReaderCitation(
    presentation: OrigReadArticleAssistantPresentation,
    targetIsCurrentArticle: Boolean,
): Boolean =
    presentation != OrigReadArticleAssistantPresentation.BottomSheet && targetIsCurrentArticle

/**
 * 使用 Android 官方 Window Size Class 断点做集中分类。
 *
 * 输入必须是当前 app window 的 dp 尺寸，而不是设备物理屏幕尺寸；Foldable posture / hinge 会在 R09.5
 * 叠加到同一 Profile 之上。
 */
internal fun origReadAdaptiveLayoutProfile(
    widthDp: Int,
    heightDp: Int,
    foldLayoutInfo: OrigReadFoldLayoutInfo = OrigReadFoldLayoutInfo.Flat,
): OrigReadAdaptiveLayoutProfile =
    OrigReadAdaptiveLayoutProfile(
        widthDp = widthDp,
        heightDp = heightDp,
        foldLayoutInfo = foldLayoutInfo,
        widthClass =
            when {
                widthDp >= 1600 -> OrigReadWindowWidthClass.ExtraLarge
                widthDp >= 1200 -> OrigReadWindowWidthClass.Large
                widthDp >= 840 -> OrigReadWindowWidthClass.Expanded
                widthDp >= 600 -> OrigReadWindowWidthClass.Medium
                else -> OrigReadWindowWidthClass.Compact
            },
        heightClass =
            when {
                heightDp >= 900 -> OrigReadWindowHeightClass.Expanded
                heightDp >= 480 -> OrigReadWindowHeightClass.Medium
                else -> OrigReadWindowHeightClass.Compact
            },
    )

val LocalOrigReadAdaptiveLayoutProfile =
    staticCompositionLocalOf {
        OrigReadAdaptiveLayoutProfile(
            widthDp = 0,
            heightDp = 0,
            widthClass = OrigReadWindowWidthClass.Compact,
            heightClass = OrigReadWindowHeightClass.Medium,
            foldLayoutInfo = OrigReadFoldLayoutInfo.Flat,
        )
    }

/**
 * 把 window 坐标系中的目标区域映射到当前 Compose 容器。
 *
 * Fold posture 切换时 Scaffold directive 与子树可能分两次重组；若目标区域暂时还不与当前容器相交，
 * 先保留当前容器内容，避免过渡帧整页消失，下一次布局后会落到目标连续区域。
 */
@Composable
fun OrigReadWindowRegionBox(
    targetRegion: OrigReadPaneRegion?,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    var containerInWindow by remember { mutableStateOf<OrigReadPaneRegion?>(null) }
    Box(
        modifier =
            modifier.onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val next =
                    with(density) {
                        OrigReadPaneRegion(
                            leftDp = position.x.toDp().value,
                            topDp = position.y.toDp().value,
                            rightDp = position.x.toDp().value + coordinates.size.width.toDp().value,
                            bottomDp = position.y.toDp().value + coordinates.size.height.toDp().value,
                        )
                    }
                if (containerInWindow != next) containerInWindow = next
            },
    ) {
        val container = containerInWindow
        val resolved =
            when {
                container == null -> null
                targetRegion == null -> container
                else -> targetRegion.intersect(container) ?: container
            }
        if (container == null || resolved == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = contentAlignment,
                content = content,
            )
        } else {
            Box(
                modifier =
                    Modifier.absoluteOffset(
                            x = (resolved.leftDp - container.leftDp).dp,
                            y = (resolved.topDp - container.topDp).dp,
                        )
                        .width(resolved.widthDp.dp)
                        .height(resolved.heightDp.dp),
                contentAlignment = contentAlignment,
                content = content,
            )
        }
    }
}

/** 大屏页面的语义化内容宽度；Reader 正文继续使用 LocalTextContentWidth。 */
enum class OrigReadContentWidth(val maxWidth: Dp) {
    Compact(720.dp),
    Comfortable(880.dp),
    Editor(1120.dp),
}

/**
 * 手机窗口小于语义上限时保持 fillMaxWidth；更宽窗口中居中限宽，避免设置项、输入框和列表无限拉伸。
 */
@Composable
fun OrigReadAdaptiveContent(
    width: OrigReadContentWidth,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val adaptiveLayoutProfile = LocalOrigReadAdaptiveLayoutProfile.current
    val density = LocalDensity.current
    var containerInWindow by remember { mutableStateOf<OrigReadPaneRegion?>(null) }
    Box(
        modifier =
            modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                val position: Offset = coordinates.positionInWindow()
                val next =
                    with(density) {
                        OrigReadPaneRegion(
                            leftDp = position.x.toDp().value,
                            topDp = position.y.toDp().value,
                            rightDp = position.x.toDp().value + coordinates.size.width.toDp().value,
                            bottomDp = position.y.toDp().value + coordinates.size.height.toDp().value,
                        )
                    }
                if (containerInWindow != next) containerInWindow = next
            },
    ) {
        val container = containerInWindow
        val safeRegion =
            if (container == null || !adaptiveLayoutProfile.foldLayoutInfo.hasSeparatingHinge) {
                container
            } else {
                adaptiveLayoutProfile.foldLayoutInfo.primarySafeRegion(container)
            }
        if (container == null || safeRegion == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier =
                        Modifier.widthIn(max = width.maxWidth)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                    content = content,
                )
            }
        } else {
            val localX = (safeRegion.leftDp - container.leftDp).dp
            val localY = (safeRegion.topDp - container.topDp).dp
            Box(
                modifier =
                    Modifier.absoluteOffset(x = localX, y = localY)
                        .width(safeRegion.widthDp.dp)
                        .height(safeRegion.heightDp.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier =
                        Modifier.widthIn(max = width.maxWidth)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                    content = content,
                )
            }
        }
    }
}
