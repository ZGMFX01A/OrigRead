package me.ash.reader.infrastructure.website

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticWebsiteRegionScorerTest {
    @Test
    fun `main latest section receives positive adjustment`() {
        val document = Jsoup.parse(
            """
            <main role="main">
              <section class="latest-news">
                <h2>最新资讯</h2>
                <div id="article-list"><article>内容</article></div>
              </section>
            </main>
            """.trimIndent()
        )

        val score = AutomaticWebsiteRegionScorer.score(document.getElementById("article-list")!!)

        assertTrue(score.adjustment > 0)
        assertTrue(score.signals.contains("main"))
    }

    @Test
    fun `sidebar popular ranking receives strong negative adjustment`() {
        val document = Jsoup.parse(
            """
            <aside class="sidebar" role="complementary">
              <section data-widget="popular-ranking">
                <h2>热门排行</h2>
                <ol id="rank-list"><li>内容</li></ol>
              </section>
            </aside>
            """.trimIndent()
        )

        val score = AutomaticWebsiteRegionScorer.score(document.getElementById("rank-list")!!)

        assertTrue(score.adjustment <= -35)
        assertTrue(score.signals.contains("aside"))
    }

    @Test
    fun `article title keywords do not affect neutral container`() {
        val document = Jsoup.parse(
            """
            <div id="plain-list" class="photo-grid domain-list">
              <article><h3>本周热门产品推荐与购买建议</h3></article>
            </div>
            """.trimIndent()
        )

        val score = AutomaticWebsiteRegionScorer.score(document.getElementById("plain-list")!!)

        assertEquals(0, score.adjustment)
    }
}
