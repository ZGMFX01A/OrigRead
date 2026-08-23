package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrigReadReleaseLinksTest {
    @Test
    fun `builds apk download link from release tag`() {
        val links =
            "https://github.com/ZGMFX01A/OrigRead/releases/tag/v1.0.1"
                .toOrigReadReleaseLinks()

        assertEquals(
            "https://github.com/ZGMFX01A/OrigRead/releases/download/v1.0.1/OrigRead-v1.0.1.apk",
            links?.apkDownloadUrl,
        )
    }

    @Test
    fun `supports release tags without v prefix`() {
        val links =
            "https://github.com/ZGMFX01A/OrigRead/releases/tag/1.0.0"
                .toOrigReadReleaseLinks()

        assertEquals(
            "https://github.com/ZGMFX01A/OrigRead/releases/download/1.0.0/OrigRead-1.0.0.apk",
            links?.apkDownloadUrl,
        )
    }

    @Test
    fun `llm edition builds llm apk download link`() {
        val links =
            "https://github.com/ZGMFX01A/OrigRead/releases/tag/v1.0.1"
                .toOrigReadReleaseLinks(llmEdition = true)

        assertEquals(
            "https://github.com/ZGMFX01A/OrigRead/releases/download/v1.0.1/OrigRead-LLM-v1.0.1.apk",
            links?.apkDownloadUrl,
        )
    }

    @Test
    fun `ignores unrelated github pages`() {
        assertNull("https://github.com/ZGMFX01A/OrigRead/commits/main".toOrigReadReleaseLinks())
    }
}
