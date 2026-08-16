package me.ash.reader.ui.page.settings.tips

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDialogLogTest {
    @Test
    fun `removes github generated full changelog section`() {
        val releaseNotes =
            """
            - 新增发布文章快捷操作，可直接下载对应版本的 APK。

            **Full Changelog**:
            https://github.com/ZGMFX01A/OrigRead/compare/v1.0.1...v1.0.2
            """.trimIndent()

        assertEquals(
            "- 新增发布文章快捷操作，可直接下载对应版本的 APK。",
            releaseNotes.withoutGeneratedFullChangelog(),
        )
    }

    @Test
    fun `keeps normal release notes unchanged`() {
        assertEquals("- 修复 AI 摘要超时", "- 修复 AI 摘要超时".withoutGeneratedFullChangelog())
    }

    @Test
    fun `formats github release timestamp as yyyy mm dd`() {
        assertEquals("2026-08-09", "2026-08-09T05:41:55Z".asReleaseDate())
        assertEquals("2026-08-09", "2026-08-09T23:59:59+08:00".asReleaseDate())
        assertEquals("", "".asReleaseDate())
    }
}
