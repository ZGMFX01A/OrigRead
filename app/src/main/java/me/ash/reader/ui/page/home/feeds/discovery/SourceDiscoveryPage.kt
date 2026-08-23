package me.ash.reader.ui.page.home.feeds.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.net.URI
import me.ash.reader.R
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.infrastructure.discovery.SourceCategoryLabels
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.ext.collectAsStateValue

/** 按上游原分类浏览内置 RSS 目录；分类仅做本地化展示，不分析或重分类 Feed 内容。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDiscoveryPage(
    onBack: () -> Unit,
    onSubscribe: (FeedCatalogEntry) -> Unit,
    viewModel: SourceDiscoveryViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateValue()
    val configuration = LocalConfiguration.current
    val languageTag = configuration.locales[0].toLanguageTag()
    var categorySheetVisible by rememberSaveable { mutableStateOf(false) }

    val selectedCategoryLabel =
        state.selectedCategory?.let { SourceCategoryLabels.localized(it, languageTag) }
            ?: stringResource(R.string.source_discovery_all_categories)

    if (categorySheetVisible) {
        CategoryFilterSheet(
            categories = state.categories,
            categoryCounts = state.categoryCounts,
            totalFeedCount = state.totalFeedCount,
            selectedCategory = state.selectedCategory,
            languageTag = languageTag,
            onSelected = { category ->
                viewModel.setCategory(category)
                categorySheetVisible = false
            },
            onDismiss = { categorySheetVisible = false },
        )
    }

    OrigReadScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.source_discovery_title)) },
                navigationIcon = {
                    FeedbackIconButton(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = onBack,
                    )
                },
            )
        },
        content = {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.clear),
                                )
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.source_discovery_search_hint)) },
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterChip(
                        selected = state.selectedCategory != null,
                        onClick = { categorySheetVisible = true },
                        label = {
                            Text(
                                text = selectedCategoryLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.source_discovery_count, state.feeds.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.feeds.isEmpty()) {
                    EmptyDiscoveryResult(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(state.feeds, key = { it.id }) { feed ->
                            SourceResultItem(
                                feed = feed,
                                languageTag = languageTag,
                                onSubscribe = { onSubscribe(feed) },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** 单个来源只展示用户做订阅决策真正需要的信息，数据集归属不再占据每一行主视觉。 */
@Composable
private fun SourceResultItem(
    feed: FeedCatalogEntry,
    languageTag: String,
    onSubscribe: () -> Unit,
) {
    val categoryText =
        remember(feed.categories, languageTag) {
            val labels =
                feed.categories
                    .map { SourceCategoryLabels.localized(it, languageTag) }
                    .distinct()
            when {
                labels.size <= 2 -> labels.joinToString(" · ")
                else -> labels.take(2).joinToString(" · ") + " +${labels.size - 2}"
            }
        }
    val host = remember(feed.siteUrl, feed.feedUrl) { feed.displayHost() }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            FeedIcon(
                feedName = feed.name,
                iconUrl = null,
                size = 40.dp,
            )
        },
        headlineContent = {
            Text(
                text = feed.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (categoryText.isNotBlank()) {
                    Text(
                        text = categoryText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            OutlinedButton(
                onClick = onSubscribe,
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text(stringResource(R.string.subscribe))
            }
        },
    )
}

/** 44 个分类放进可滚动网格，而不是在主页面横向铺满几十个 Chip。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterSheet(
    categories: List<String>,
    categoryCounts: Map<String, Int>,
    totalFeedCount: Int,
    selectedCategory: String?,
    languageTag: String,
    onSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.source_discovery_choose_category),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.source_discovery_category_sheet_hint,
                                categories.size,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectedCategory != null) {
                    TextButton(onClick = { onSelected(null) }) {
                        Text(stringResource(R.string.source_discovery_clear_filter))
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "__all__") {
                    CategoryTile(
                        title = stringResource(R.string.source_discovery_all_categories),
                        subtitle =
                            if (languageTag.lowercase().startsWith("zh")) {
                                "All categories"
                            } else {
                                null
                            },
                        count = totalFeedCount,
                        selected = selectedCategory == null,
                        onClick = { onSelected(null) },
                    )
                }
                items(categories, key = { it }) { category ->
                    CategoryTile(
                        title = SourceCategoryLabels.localized(category, languageTag),
                        subtitle = SourceCategoryLabels.secondary(category, languageTag),
                        count = categoryCounts[category] ?: 0,
                        selected = selectedCategory == category,
                        onClick = { onSelected(category) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    title: String,
    subtitle: String?,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
        shape = RoundedCornerShape(16.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        border =
            BorderStroke(
                width = 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.source_discovery_count, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyDiscoveryResult(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.source_discovery_no_results),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.source_discovery_no_results_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun FeedCatalogEntry.displayHost(): String {
    val sourceUrl = siteUrl?.takeIf(String::isNotBlank) ?: feedUrl
    return runCatching { URI(sourceUrl).host?.removePrefix("www.") }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: feedUrl
}
