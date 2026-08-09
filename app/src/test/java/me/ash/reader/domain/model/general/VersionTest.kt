package me.ash.reader.domain.model.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun `github release tag with v prefix is parsed correctly`() {
        assertEquals("1.0.0", "v1.0.0".toVersion().toString())
        assertEquals("1.0.0", "V1.0.0".toVersion().toString())
    }

    @Test
    fun `release version 1 0 0 is newer than installed 0 17 0`() {
        val latest = "v1.0.0".toVersion()
        val current = "0.17.0".toVersion()

        assertTrue(latest > current)
        assertTrue(latest.whetherNeedUpdate(current, Version()))
    }

    @Test
    fun `same version does not require update`() {
        val latest = "v1.0.0".toVersion()
        val current = "1.0.0".toVersion()

        assertFalse(latest.whetherNeedUpdate(current, Version()))
    }

    @Test
    fun `prerelease suffix does not break numeric version parsing`() {
        assertEquals("1.2.3", "v1.2.3-beta.1".toVersion().toString())
    }
}
