package me.ash.reader.infrastructure.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

class LatestReleaseCheckTest {
    private val repositoryUrl = "https://github.com/ZGMFX01A/OrigRead"
    private val officialUrl = "https://api.github.com/repos/ZGMFX01A/OrigRead/releases/latest"
    private val versionUrl = "https://data.jsdelivr.com/v1/package/gh/ZGMFX01A/OrigRead"

    @Test
    fun `invalid candidate metadata falls back to official release`() = runBlocking {
        val invalidUrl = "https://example.test/releases/latest"
        val officialRelease = officialRelease("v1.2.0")
        val source =
            FakeNetworkDataSource(
                apiResponder = { url ->
                    when (url) {
                        invalidUrl -> Response.success(LatestRelease())
                        officialUrl -> Response.success(officialRelease)
                        else -> error("unexpected URL: $url")
                    }
                },
            )

        val result = source.getTrustedLatestRelease(listOf(invalidUrl, officialUrl), repositoryUrl)

        assertEquals(listOf(invalidUrl, officialUrl), source.apiCalls)
        assertNotNull(result.release)
        assertEquals("v1.2.0", result.release?.tag_name)
    }

    @Test
    fun `jsdelivr version index builds trusted standard release`() = runBlocking {
        val source =
            FakeNetworkDataSource(
                downloadResponder = {
                    """{"tags":{},"versions":["1.3.0","1.4.0-beta.1","1.4.0","1.2.0"]}"""
                        .jsonBody()
                },
                probeResponder = { _, _ -> Response.success("x".toResponseBody()) },
            )

        val release =
            source.getTrustedLatestReleaseVersion(
                urls = listOf(versionUrl),
                repositoryUrl = repositoryUrl,
                llmEdition = false,
            )

        assertEquals(listOf(versionUrl), source.downloadCalls)
        assertEquals(
            listOf(
                ProbeCall(
                    "$repositoryUrl/releases/download/v1.4.0/OrigRead-v1.4.0.apk",
                    "bytes=0-0",
                )
            ),
            source.probeCalls,
        )
        assertEquals("v1.4.0", release?.tag_name)
        assertEquals("$repositoryUrl/releases/tag/v1.4.0", release?.html_url)
        assertEquals(
            "$repositoryUrl/releases/download/v1.4.0/OrigRead-v1.4.0.apk",
            release?.assets?.single()?.browser_download_url,
        )
    }

    @Test
    fun `jsdelivr skips tag whose X edition asset is missing`() = runBlocking {
        val x140 = "$repositoryUrl/releases/download/v1.4.0/OrigRead-X-v1.4.0.apk"
        val x130 = "$repositoryUrl/releases/download/v1.3.0/OrigRead-X-v1.3.0.apk"
        val source =
            FakeNetworkDataSource(
                downloadResponder = { """{"versions":["1.3.0","1.4.0"]}""".jsonBody() },
                probeResponder = { url, _ ->
                    when (url) {
                        x140 -> Response.error(404, "missing".toResponseBody())
                        x130 -> Response.success("x".toResponseBody())
                        else -> error("unexpected probe URL: $url")
                    }
                },
            )

        val release =
            source.getTrustedLatestReleaseVersion(
                urls = listOf(versionUrl),
                repositoryUrl = repositoryUrl,
                llmEdition = true,
            )

        assertEquals("v1.3.0", release?.tag_name)
        assertEquals(x130, release?.assets?.single()?.browser_download_url)
        assertEquals(
            listOf(ProbeCall(x140, "bytes=0-0"), ProbeCall(x130, "bytes=0-0")),
            source.probeCalls,
        )
    }

    @Test
    fun `mainland reliable check uses version index before optional github metadata`() = runBlocking {
        val source =
            FakeNetworkDataSource(
                apiResponder = { throw IOException("GitHub API unavailable") },
                downloadResponder = { """{"versions":["1.4.0","1.3.0"]}""".jsonBody() },
                probeResponder = { _, _ -> Response.success("x".toResponseBody()) },
            )

        val result =
            source.getReliableLatestRelease(
                apiUrls = listOf(officialUrl),
                versionUrls = listOf(versionUrl),
                repositoryUrl = repositoryUrl,
                llmEdition = false,
                preferVersionIndex = true,
            )

        assertEquals("v1.4.0", result.release?.tag_name)
        assertEquals(listOf(officialUrl), source.apiCalls)
        assertEquals(listOf(versionUrl), source.downloadCalls)
    }

    @Test
    fun `version index enriches latest release with official metadata when api is reachable`() = runBlocking {
        val official =
            officialRelease("v1.4.0").copy(
                body = "- 修复更新下载",
                published_at = "2026-08-22T19:21:53Z",
                assets =
                    listOf(
                        AssetsItem(
                            name = "OrigRead-v1.4.0.apk",
                            content_type = "application/vnd.android.package-archive",
                            size = 12_345,
                            browser_download_url =
                                "$repositoryUrl/releases/download/v1.4.0/OrigRead-v1.4.0.apk",
                        )
                    ),
            )
        val source =
            FakeNetworkDataSource(
                apiResponder = { Response.success(official) },
                downloadResponder = { """{"versions":["1.4.0"]}""".jsonBody() },
                probeResponder = { _, _ -> Response.success("x".toResponseBody()) },
            )

        val result =
            source.getReliableLatestRelease(
                apiUrls = listOf(officialUrl),
                versionUrls = listOf(versionUrl),
                repositoryUrl = repositoryUrl,
                llmEdition = false,
                preferVersionIndex = true,
            )

        assertEquals("v1.4.0", result.release?.tag_name)
        assertEquals("- 修复更新下载", result.release?.body)
        assertEquals("2026-08-22T19:21:53Z", result.release?.published_at)
        assertEquals(12_345, result.release?.assets?.single()?.size)
        assertEquals(listOf(officialUrl), source.apiCalls)
    }

    @Test
    fun `missing indexed edition assets fall back to official github api`() = runBlocking {
        val source =
            FakeNetworkDataSource(
                apiResponder = { Response.success(officialRelease("v1.2.0")) },
                downloadResponder = { """{"versions":["1.4.0","1.3.0"]}""".jsonBody() },
                probeResponder = { _, _ -> Response.error(404, "missing".toResponseBody()) },
            )

        val result =
            source.getReliableLatestRelease(
                apiUrls = listOf(officialUrl),
                versionUrls = listOf(versionUrl),
                repositoryUrl = repositoryUrl,
                llmEdition = true,
                preferVersionIndex = true,
            )

        assertEquals("v1.2.0", result.release?.tag_name)
        assertEquals(2, source.probeCalls.size)
        assertEquals(listOf(officialUrl), source.apiCalls)
    }

    @Test
    fun `ambiguous asset probe failure falls back to official github api instead of older tag`() = runBlocking {
        val source =
            FakeNetworkDataSource(
                apiResponder = { Response.success(officialRelease("v1.4.0")) },
                downloadResponder = { """{"versions":["1.4.0","1.3.0"]}""".jsonBody() },
                probeResponder = { _, _ -> Response.error(403, "forbidden".toResponseBody()) },
            )

        val result =
            source.getReliableLatestRelease(
                apiUrls = listOf(officialUrl),
                versionUrls = listOf(versionUrl),
                repositoryUrl = repositoryUrl,
                llmEdition = true,
                preferVersionIndex = true,
            )

        assertEquals("v1.4.0", result.release?.tag_name)
        assertEquals(1, source.probeCalls.size)
        assertEquals(listOf(officialUrl), source.apiCalls)
    }

    @Test
    fun `version index failure falls back to official github api`() = runBlocking {
        val source =
            FakeNetworkDataSource(
                apiResponder = { Response.success(officialRelease("v1.4.0")) },
                downloadResponder = { throw IOException("jsDelivr unavailable") },
            )

        val result =
            source.getReliableLatestRelease(
                apiUrls = listOf(officialUrl),
                versionUrls = listOf(versionUrl),
                repositoryUrl = repositoryUrl,
                llmEdition = false,
                preferVersionIndex = true,
            )

        assertEquals("v1.4.0", result.release?.tag_name)
        assertEquals(listOf(versionUrl), source.downloadCalls)
        assertEquals(listOf(officialUrl), source.apiCalls)
    }

    private fun officialRelease(tag: String) =
        LatestRelease(
            html_url = "$repositoryUrl/releases/tag/$tag",
            tag_name = tag,
        )

    private fun String.jsonBody(): ResponseBody =
        toResponseBody("application/json; charset=utf-8".toMediaType())

    private data class ProbeCall(
        val url: String,
        val range: String,
    )

    private class FakeNetworkDataSource(
        private val apiResponder: suspend (String) -> Response<LatestRelease> = {
            error("getReleaseLatest is not expected: $it")
        },
        private val downloadResponder: suspend (String) -> ResponseBody = {
            error("downloadFile is not expected: $it")
        },
        private val probeResponder: suspend (String, String) -> Response<ResponseBody> = { url, _ ->
            error("probeFile is not expected: $url")
        },
    ) : NetworkDataSource {
        val apiCalls = mutableListOf<String>()
        val downloadCalls = mutableListOf<String>()
        val probeCalls = mutableListOf<ProbeCall>()

        override suspend fun getReleaseLatest(url: String): Response<LatestRelease> {
            apiCalls += url
            return apiResponder(url)
        }

        override suspend fun downloadFile(url: String): ResponseBody {
            downloadCalls += url
            return downloadResponder(url)
        }

        override suspend fun probeFile(url: String, range: String): Response<ResponseBody> {
            probeCalls += ProbeCall(url, range)
            return probeResponder(url, range)
        }
    }
}
