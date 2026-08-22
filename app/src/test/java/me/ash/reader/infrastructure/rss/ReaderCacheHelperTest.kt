package me.ash.reader.infrastructure.rss

import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderCacheHelperTest {
    @Test
    fun `cache helper does not retain a shared MessageDigest instance`() {
        assertFalse(
            ReaderCacheHelper::class.java.declaredFields.any { it.type.name == "java.security.MessageDigest" }
        )
    }
}
