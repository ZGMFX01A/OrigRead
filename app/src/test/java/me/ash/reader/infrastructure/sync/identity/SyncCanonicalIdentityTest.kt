package me.ash.reader.infrastructure.sync.identity

import me.ash.reader.domain.model.feed.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncCanonicalIdentityTest {
    @Test
    fun `canonical identity v1 matches frozen cross-platform fixtures`() {
        val feedKey =
            SyncCanonicalIdentity.feedKey(
                SourceType.RSS,
                " HTTPS://Example.COM:443/feed/?utm_source=x&b=2#frag ",
            )
        assertEquals(
            "feed:v1:5db48e420506f52ed082918bb7489132f060cac350554a79696de83908b28309",
            feedKey,
        )
        assertEquals(
            "article:v1:3fe23e061e57a17896fd9bb1e52e1b9845f0c48e38c237b088ad24a5191ca789",
            SyncCanonicalIdentity.articleKey(
                feedKey,
                "https://EXAMPLE.com/post/42/?utm_medium=x#part",
            ),
        )
        assertEquals(
            "rel:v1:5b5f5125f29862e934c2f7e2a358f3180338d5e4db7ae7eaec6ce81b79a54955",
            SyncCanonicalIdentity.relationSyncId(
                SyncEntityType.CONVERSATION_ARTICLE,
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
            ),
        )
        assertEquals(
            "local-rel:v1:de47eb320197d3b649d9583c67f3fae20c88862e768a8b5e23be2c9c15436508",
            SyncCanonicalIdentity.relationLocalId(
                SyncEntityType.CONVERSATION_ARTICLE,
                "local-conversation",
                "local-article",
            ),
        )
    }

    @Test
    fun `uuid adoption is canonical and article without link stays unaliased`() {
        assertEquals(
            "123e4567-e89b-42d3-a456-426614174000",
            SyncCanonicalIdentity.adoptUuidOrNull("123E4567-E89B-42D3-A456-426614174000"),
        )
        assertNull(SyncCanonicalIdentity.adoptUuidOrNull("local-article-1"))
        assertNull(SyncCanonicalIdentity.articleKey("feed:v1:any", "  "))
    }
}
