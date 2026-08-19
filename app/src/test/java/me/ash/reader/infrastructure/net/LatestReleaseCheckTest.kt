package me.ash.reader.infrastructure.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

class LatestReleaseCheckTest {
    private val repositoryUrl = "https://github.com/ZGMFX01A/OrigRead"
    private val mirrorUrl = "https://gh-proxy.com/https://api.github.com/repos/ZGMFX01A/OrigRead/releases/latest"
    private val officialUrl = "https://api.github.com/repos/ZGMFX01A/OrigRead/releases/latest"

    @Test
    fun `invalid mirror metadata falls back to official release`() = runBlocking {
        val officialRelease =
            LatestRelease(
                html_url = "$repositoryUrl/releases/tag/v1.2.0",
                tag_name = "v1.2.0",
            )
        val source =
            FakeNetworkDataSource { url ->
                when (url) {
                    mirrorUrl -> Response.success(LatestRelease())
                    officialUrl -> Response.success(officialRelease)
                    else -> error("unexpected URL: $url")
                }
            }

        val result = source.getTrustedLatestRelease(listOf(mirrorUrl, officialUrl), repositoryUrl)

        assertEquals(listOf(mirrorUrl, officialUrl), source.calls)
        assertNotNull(result.release)
        assertEquals("v1.2.0", result.release?.tag_name)
    }

    @Test
    fun `mirror network failure falls back to official release`() = runBlocking {
        val officialRelease =
            LatestRelease(
                html_url = "$repositoryUrl/releases/tag/v1.2.0",
                tag_name = "v1.2.0",
            )
        val source =
            FakeNetworkDataSource { url ->
                when (url) {
                    mirrorUrl -> throw IOException("mirror unavailable")
                    officialUrl -> Response.success(officialRelease)
                    else -> error("unexpected URL: $url")
                }
            }

        val result = source.getTrustedLatestRelease(listOf(mirrorUrl, officialUrl), repositoryUrl)

        assertEquals(listOf(mirrorUrl, officialUrl), source.calls)
        assertEquals("v1.2.0", result.release?.tag_name)
    }

    private class FakeNetworkDataSource(
        private val responder: suspend (String) -> Response<LatestRelease>,
    ) : NetworkDataSource {
        val calls = mutableListOf<String>()

        override suspend fun getReleaseLatest(url: String): Response<LatestRelease> {
            calls += url
            return responder(url)
        }

        override suspend fun downloadFile(url: String): ResponseBody =
            error("downloadFile is not used by this test: $url")
    }
}
