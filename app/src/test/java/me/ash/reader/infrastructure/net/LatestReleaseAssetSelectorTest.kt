package me.ash.reader.infrastructure.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestReleaseAssetSelectorTest {

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
}
