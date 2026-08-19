package me.ash.reader.infrastructure.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestReleaseAssetSelectorTest {
    private val repositoryUrl = "https://github.com/ZGMFX01A/OrigRead"

    @Test
    fun `优先选择 OrigRead release apk 而不是第一项资产`() {
        val release =
            LatestRelease(
                assets =
                    listOf(
                        AssetsItem(name = "checksums.txt", browser_download_url = "https://example.com/checksums.txt"),
                        AssetsItem(name = "OrigRead-v0.18.0.apk", browser_download_url = "https://example.com/release.apk"),
                        AssetsItem(name = "OrigRead-v0.18.0-debug.apk", browser_download_url = "https://example.com/debug.apk"),
                    ),
            )

        assertEquals("https://example.com/release.apk", release.preferredApkAsset()?.browser_download_url)
    }

    @Test
    fun `没有 apk 资产时返回空`() {
        val release =
            LatestRelease(
                assets = listOf(AssetsItem(name = "source.zip", browser_download_url = "https://example.com/source.zip")),
            )

        assertNull(release.preferredApkAsset())
    }

    @Test
    fun `镜像元数据必须指向官方 release 页面和安装包`() {
        val release =
            LatestRelease(
                html_url = "$repositoryUrl/releases/tag/v1.2.0",
                tag_name = "v1.2.0",
                assets =
                    listOf(
                        AssetsItem(
                            name = "OrigRead-malicious.apk",
                            browser_download_url = "https://example.com/malicious.apk",
                        ),
                        AssetsItem(
                            name = "OrigRead-v1.2.0.apk",
                            browser_download_url = "$repositoryUrl/releases/download/v1.2.0/OrigRead-v1.2.0.apk",
                        ),
                    ),
            )

        assertEquals(true, release.isTrustedReleaseMetadata(repositoryUrl))
        assertEquals(
            "$repositoryUrl/releases/download/v1.2.0/OrigRead-v1.2.0.apk",
            release.preferredTrustedApkAsset(repositoryUrl)?.browser_download_url,
        )
    }

    @Test
    fun `无效 release 页面不能被镜像元数据接受`() {
        val release =
            LatestRelease(
                html_url = "https://example.com/releases/tag/v1.2.0",
                tag_name = "v1.2.0",
            )

        assertEquals(false, release.isTrustedReleaseMetadata(repositoryUrl))
    }

    @Test
    fun `release 页面版本必须与 tag_name 一致`() {
        val release =
            LatestRelease(
                html_url = "$repositoryUrl/releases/tag/v1.1.0",
                tag_name = "v1.2.0",
            )

        assertEquals(false, release.isTrustedReleaseMetadata(repositoryUrl))
    }
}
