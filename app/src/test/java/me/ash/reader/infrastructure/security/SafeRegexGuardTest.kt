package me.ash.reader.infrastructure.security

import android.content.Context
import java.nio.file.Files
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SafeRegexGuardTest {
    @Test
    fun `rejects catastrophic nested quantifiers overlapping alternations backreferences and overlong patterns`() {
        listOf(
            "(a+)+",
            "([a-z]+)*",
            "(a|aa)+",
            "(a)\\1",
            "a{1001}",
            "a".repeat(SafeRegexGuard.MAX_PATTERN_LENGTH + 1),
            "(?i)^+",
        ).forEach { unsafe ->
            assertThrows("Expected SafeRegexGuard to reject $unsafe", IllegalArgumentException::class.java) {
                SafeRegexGuard.validate(unsafe)
            }
        }
    }

    @Test
    fun `allows built-in website rules and safe regular expressions`() {
        val dir = Files.createTempDirectory("origread-saferegex").toFile()
        try {
            val context = mock<Context>()
            whenever(context.filesDir).thenReturn(dir)
            val websiteRepo = WebsiteRuleRepository(context)
            websiteRepo.listRules().forEach { rule ->
                rule.includeUrlRegex?.let(SafeRegexGuard::validate)
                rule.excludeTitleRegexes.forEach(SafeRegexGuard::validate)
                rule.urlIdRegex?.let(SafeRegexGuard::validate)
            }

            SafeRegexGuard.compile("rtx\\s+50\\d{2}", setOf(RegexOption.IGNORE_CASE))
            SafeRegexGuard.compile("^https?://(?:www\\.)?example\\.com/.*?$", setOf(RegexOption.IGNORE_CASE))

            val filterRepo = ArticleFilterRepository(context)
            assertThrows(IllegalArgumentException::class.java) {
                filterRepo.add(keyword = "(a+)+", type = ArticleFilterRuleType.REGEX)
            }
            val unsafeWebsiteRule =
                WebsiteRule(
                    id = "evil",
                    name = "Evil",
                    hosts = listOf("example.com"),
                    articleSelectors = listOf("article"),
                    titleSelector = "h2",
                    linkSelector = "a",
                    includeUrlRegex = "([a-z]+)*",
                )
            val websiteError = runCatching { websiteRepo.validateCandidate(unsafeWebsiteRule) }.exceptionOrNull()
            assertTrue(websiteError is IllegalArgumentException)
        } finally {
            dir.deleteRecursively()
        }
    }
}
