package me.ash.reader.infrastructure.net

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubDownloadMirrorTest {
    private val releaseUrl =
        "https://github.com/ZGMFX01A/OrigRead/releases/download/v1.2.0/OrigRead-1.2.0.apk"
    private val latestApiUrl =
        "https://api.github.com/repos/ZGMFX01A/OrigRead/releases/latest"

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
            listOf(latestApiUrl),
            githubReleaseDownloadCandidates(
                latestApiUrl,
                preferMirror = true,
            ),
        )
    }

    @Test
    fun `mainland update checks prefer mirror and fall back to github`() {
        assertEquals(
            listOf("https://gh-proxy.com/$latestApiUrl", latestApiUrl),
            githubReleaseCheckCandidates(latestApiUrl, preferMirror = true),
        )
    }

    @Test
    fun `does not proxy unrelated api urls during update checks`() {
        assertEquals(
            listOf("https://api.github.com/repos/ZGMFX01A/OrigRead/issues"),
            githubReleaseCheckCandidates(
                "https://api.github.com/repos/ZGMFX01A/OrigRead/issues",
                preferMirror = true,
            ),
        )
    }
}
