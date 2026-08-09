package me.ash.reader.ui.page.home.feeds.discovery

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.infrastructure.discovery.FeedCatalogSource
import me.ash.reader.infrastructure.discovery.FeedDiscoveryCatalog
import me.ash.reader.infrastructure.discovery.SourceCategoryLabels

data class SourceDiscoveryUiState(
    val query: String = "",
    val selectedCategory: String? = null,
    val categories: List<String> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val totalFeedCount: Int = 0,
    val feeds: List<FeedCatalogEntry> = emptyList(),
    val sources: Map<String, FeedCatalogSource> = emptyMap(),
)

/** 仅在本地目录上做名称、URL 与原分类筛选，不触发 Feed 网络请求。 */
@HiltViewModel
class SourceDiscoveryViewModel @Inject constructor(
    private val catalog: FeedDiscoveryCatalog,
) : ViewModel() {
    private val allFeeds = catalog.data.feeds
    private val categoryCounts =
        allFeeds
            .flatMap { it.categories.distinct() }
            .groupingBy { it }
            .eachCount()
    private val _uiState =
        MutableStateFlow(
            SourceDiscoveryUiState(
                categories =
                    catalog.data.categories.sortedWith(
                        compareByDescending<String> { categoryCounts[it] ?: 0 }.thenBy { it }
                    ),
                categoryCounts = categoryCounts,
                totalFeedCount = allFeeds.size,
                feeds = allFeeds,
                sources = catalog.data.sources.associateBy { it.id },
            )
        )
    val uiState: StateFlow<SourceDiscoveryUiState> = _uiState.asStateFlow()

    fun setQuery(value: String) {
        _uiState.update { current -> current.copy(query = value).withFilteredFeeds() }
    }

    fun setCategory(value: String?) {
        _uiState.update { current -> current.copy(selectedCategory = value).withFilteredFeeds() }
    }

    private fun SourceDiscoveryUiState.withFilteredFeeds(): SourceDiscoveryUiState {
        val normalizedQuery = query.trim().lowercase()
        val filtered =
            allFeeds.filter { feed ->
                val matchesCategory =
                    selectedCategory == null || selectedCategory in feed.categories
                val matchesQuery =
                    normalizedQuery.isBlank() ||
                        feed.name.contains(normalizedQuery, ignoreCase = true) ||
                        feed.feedUrl.contains(normalizedQuery, ignoreCase = true) ||
                        feed.categories.any { category ->
                            SourceCategoryLabels.searchTerms(category).any { term ->
                                term.contains(normalizedQuery, ignoreCase = true)
                            }
                        }
                matchesCategory && matchesQuery
            }
        return copy(feeds = filtered)
    }
}
