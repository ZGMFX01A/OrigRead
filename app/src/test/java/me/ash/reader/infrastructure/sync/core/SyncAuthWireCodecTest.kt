package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncAuthWireCodecTest {
    @Test
    fun `auth fixture binds sequence in framed id and digest`() {
        val payload = "{\"ownerDeviceId\":\"device-owner\",\"spaceName\":\"demo\"}"
        val payloadHash = SyncOperationCanonicalizer.sha256Hex(payload)
        val objectId =
            SyncAuthWireCodec.authObjectId(
                syncSpaceId = "space-1",
                authEpoch = 0,
                objectType = SyncAuthObjectType.SPACE_ROOT,
                authorDeviceId = "device-owner",
                payloadHash = payloadHash,
            )
        val objectValue =
            SyncAuthProtocolObject(
                authObjectId = objectId,
                syncSpaceId = "space-1",
                authEpoch = 0,
                objectType = SyncAuthObjectType.SPACE_ROOT,
                authorDeviceId = "device-owner",
                ownerDeviceId = "device-owner",
                payloadJson = payload,
                payloadHash = payloadHash,
                signingDigest = "",
                authorSignature = "fixture-signature",
            ).let { it.copy(signingDigest = SyncAuthWireCodec.signingDigest(it)) }

        SyncAuthWireCodec.validate(objectValue)

        assertEquals(
            "auth1:44872226230272f9f37bb07f362946b65d1f0980c95b30cfc5007a006a946126",
            objectValue.authObjectId,
        )
        assertEquals(
            "35f3791c574855068f96321bb7831513f30516096911492dc2df2dce9abc927c",
            objectValue.signingDigest,
        )
        assertEquals(objectValue, SyncAuthWireCodec.decode(SyncAuthWireCodec.encode(objectValue)))
    }

    @Test
    fun `non canonical payload is rejected`() {
        val payload = "{\"z\":1,\"a\":2}"
        val payloadHash = SyncOperationCanonicalizer.sha256Hex(payload)
        val objectValue =
            SyncAuthProtocolObject(
                authObjectId =
                    SyncAuthWireCodec.authObjectId(
                        "space-1",
                        0,
                        SyncAuthObjectType.SPACE_ROOT,
                        "device-owner",
                        payloadHash,
                    ),
                syncSpaceId = "space-1",
                authEpoch = 0,
                objectType = SyncAuthObjectType.SPACE_ROOT,
                authorDeviceId = "device-owner",
                ownerDeviceId = "device-owner",
                payloadJson = payload,
                payloadHash = payloadHash,
                signingDigest = "placeholder",
                authorSignature = "fixture-signature",
            )

        runCatching { SyncAuthWireCodec.validate(objectValue) }
            .onSuccess { error("Expected non-canonical payload to be rejected") }
    }
}
