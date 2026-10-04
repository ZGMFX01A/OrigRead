package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPagedRecoveryBlobRefsTest {
    @Test fun snapshotReferenceCountDoesNotBreakBlobRecovery() {
        val hash = "a".repeat(64)
        val raw = """{"blobRefs":[{"field":"contentSnapshot","referenceKind":"context_content","manifest":{"hash":"$hash","totalBytes":3,"mediaType":"text/plain; charset=utf-8","durability":"SYNC_DURABLE","referenceCount":0}}]}"""
        val fields = Json.parseToJsonElement(raw).jsonObject
        assertEquals(setOf(hash), recoveryBlobHashes(fields))
        // Decoding is a projection only: the signed source object is retained verbatim.
        assertEquals(raw, fields.toString())
    }

    @Test fun missingAndEmptyReferencesAreEmpty() {
        assertTrue(recoveryBlobHashes(Json.parseToJsonElement("{}").jsonObject).isEmpty())
        assertTrue(recoveryBlobHashes(Json.parseToJsonElement("{\"blobRefs\":[]}").jsonObject).isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun malformedReferenceCollectionIsRejected() {
        recoveryBlobHashes(Json.parseToJsonElement("{\"blobRefs\":{}}").jsonObject)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteManifestIsNotSilentlyDropped() {
        recoveryBlobHashes(Json.parseToJsonElement("""{"blobRefs":[{"field":"textSnapshot","referenceKind":"evidence_text","manifest":{"referenceCount":0}}]}""").jsonObject)
    }
}
