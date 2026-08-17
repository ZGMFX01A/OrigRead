package me.ash.reader.infrastructure.net

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubDownloadMirrorTest {
    private val releaseUrl =
        "https://github.com/ZGMFX01A/OrigRead/releases/download/v1.2.0/OrigRead-1.2.0.apk"

    @Test
    fun `mainland order prefers mirror and always falls back to github`() {
        assertEquals(
            listOf("https://gh-proxy.com/$releaseUrl", releaseUrl),
            githubReleaseDownloadCandidates(releaseUrl, preferMirror = true),
        )
    }

    @Test
    fun `non mainland downloads use github directly without third party mirror`() {
        assertEquals(
            listOf(releaseUrl),
            githubReleaseDownloadCandidates(releaseUrl, preferMirror = false),
        )
    }

    @Test
    fun `does not proxy non release urls`() {
        assertEquals(
            listOf("https://api.github.com/repos/ZGMFX01A/OrigRead/releases/latest"),
            githubReleaseDownloadCandidates(
                "https://api.github.com/repos/ZGMFX01A/OrigRead/releases/latest",
                preferMirror = true,
            ),
        )
    }
}
