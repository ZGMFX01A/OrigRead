package me.ash.reader.infrastructure.rsshub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RssHubRouteMatcherInstrumentedTest {
    @Test
    fun clsHomeMatchesBuiltInRoutesOnAndroidRuntime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val matcher = RssHubRouteMatcher(RssHubRouteCatalog(context))
        val matches = matcher.match(
            inputUrl = "https://www.cls.cn/",
            instanceBaseUrl = "https://rsshub.example.com",
            maxResults = 8,
        )

        assertTrue(matches.any { it.route.target == "/cls/hot" })
        assertTrue(matches.any { it.route.target == "/cls/telegraph" })
    }
}
