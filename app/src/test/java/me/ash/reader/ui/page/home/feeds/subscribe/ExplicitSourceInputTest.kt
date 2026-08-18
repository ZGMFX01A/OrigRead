package me.ash.reader.ui.page.home.feeds.subscribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitSourceInputTest {
    @Test
    fun `wordpress rest endpoint is always classified as explicit json`() {
        assertTrue(
            isExplicitJsonEndpoint(
                "https://engineering.fb.com/wp-json/wp/v2/posts?_embed=1&per_page=30"
            )
        )
    }

    @Test
    fun `ordinary website does not enter json only path`() {
        assertFalse(isExplicitJsonEndpoint("https://engineering.fb.com/"))
    }
}
