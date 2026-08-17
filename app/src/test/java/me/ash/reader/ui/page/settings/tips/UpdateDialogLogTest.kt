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

    @Test
    fun `selects release notes by current app language`() {
        val notes =
            """
            ## 中文
            - 修复 AI 摘要过长
            - 优化更新下载

            ## English
            - Fix overly long AI summaries
            - Improve update downloads
            """.trimIndent()

        assertEquals(
            "- 修复 AI 摘要过长\n- 优化更新下载",
            notes.localizedReleaseNotes("zh-CN"),
        )
        assertEquals(
            "- Fix overly long AI summaries\n- Improve update downloads",
            notes.localizedReleaseNotes("en-US"),
        )
    }

    @Test
    fun `selects hidden release note language markers`() {
        val notes =
            """
            <!-- lang:zh -->
            - 修复更新功能

            <!-- lang:en -->
            - Fix update handling
            """.trimIndent()

        assertEquals("- 修复更新功能", notes.localizedReleaseNotes("zh-CN"))
        assertEquals("- Fix update handling", notes.localizedReleaseNotes("en-US"))
    }

    @Test
    fun `keeps legacy single language release notes`() {
        val notes = "- 修复若干问题\n- 优化阅读体验"
        assertEquals(notes, notes.localizedReleaseNotes("en-US"))
    }
}
