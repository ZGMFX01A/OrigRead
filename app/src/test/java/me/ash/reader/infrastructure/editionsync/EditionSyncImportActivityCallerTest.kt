package me.ash.reader.infrastructure.editionsync

import org.junit.Assert.*
import org.junit.Test

class EditionSyncImportActivityCallerTest {
    private val peer = "me.ash.reader.llm"
    @Test fun `outdated target without signature permission is rejected before transfer`() {
        validateEditionSyncTargetPermission(EDITION_SYNC_PERMISSION)
        listOf(null, "another.permission").forEach {
            assertThrows(IllegalArgumentException::class.java) { validateEditionSyncTargetPermission(it) }
        }
    }
    @Test fun `only system peer with matching extra authority and signature is accepted`() {
        validateEditionSyncCaller(peer, peer, peer, "$peer.fileprovider") { true }
    }
    @Test fun `missing or forged caller and extra are rejected before signature lookup`() {
        listOf(null to peer, "evil.app" to peer, peer to "evil.app").forEach { (caller, extra) ->
            assertThrows(IllegalArgumentException::class.java) {
                validateEditionSyncCaller(caller, peer, extra, "$peer.fileprovider") {
                    fail("untrusted caller must be rejected first"); true
                }
            }
        }
    }
    @Test fun `foreign or missing authority is rejected before signature lookup`() {
        listOf(null, "evil.app.fileprovider").forEach { authority ->
            assertThrows(IllegalArgumentException::class.java) {
                validateEditionSyncCaller(peer, peer, peer, authority) { fail("authority must be checked first"); true }
            }
        }
    }
    @Test fun `signature mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEditionSyncCaller(peer, peer, peer, "$peer.fileprovider") { false }
        }
    }
}
