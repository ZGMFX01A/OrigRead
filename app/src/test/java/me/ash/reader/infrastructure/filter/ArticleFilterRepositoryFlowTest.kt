package me.ash.reader.infrastructure.filter

import android.content.Context
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ArticleFilterRepositoryFlowTest {
    @Test
    fun `rule flow emits on rule changes but not statistics updates`() = runBlocking {
        val filesDir = Files.createTempDirectory("origread-filter-rules").toFile()
        try {
            val context = mock<Context>()
            whenever(context.filesDir).thenReturn(filesDir)
            val repository = ArticleFilterRepository(context)
            val emissions = mutableListOf<List<ArticleFilterRule>>()
            val collectJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.rulesFlow.take(3).toList(emissions)
                }

            repository.add(keyword = "blocked")
            yield()
            val addedRule = repository.getAll().single()
            repository.recordFilteredArticles(
                listOf(
                    FilteredArticleRecord(
                        articleId = "article-1",
                        feedId = "feed-1",
                        sourceName = "Test Feed",
                        title = "blocked article",
                        matchedRule = addedRule.keyword,
                        filteredAt = 1L,
                    )
                )
            )
            yield()
            repository.delete(addedRule)
            yield()
            collectJob.join()

            assertEquals(listOf(0, 1, 0), emissions.map { it.size })
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
