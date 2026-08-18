package me.ash.reader.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAccountMappingTest {

    @Test
    fun `Google Reader 默认分组不应下发为远端 label`() {
        val defaultGroupId = "7\$read_you_app_default_group"

        assertNull(resolveGoogleReaderRemoteCategoryId(defaultGroupId, defaultGroupId))
        assertEquals(
            "Tech",
            resolveGoogleReaderRemoteCategoryId("7\$Tech", defaultGroupId),
        )
        assertEquals(
            "Tech",
            resolveGoogleReaderRemoteCategoryId("7\$user/-/label/Tech", defaultGroupId),
        )
    }

    @Test
    fun `Fever 无分组 feed 回退到本地默认分组`() {
        val mapping = mapOf("7" to "5")

        assertEquals(
            "3\$5",
            FeverRssService.resolveFeedGroupId(
                accountId = 3,
                remoteFeedId = "7",
                feedsGroupsMap = mapping,
                defaultGroupId = "3\$read_you_app_default_group",
            ),
        )
        assertEquals(
            "3\$read_you_app_default_group",
            FeverRssService.resolveFeedGroupId(
                accountId = 3,
                remoteFeedId = "8",
                feedsGroupsMap = mapping,
                defaultGroupId = "3\$read_you_app_default_group",
            ),
        )
    }
}
