package me.ash.reader.infrastructure.sync.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SyncHandshakeHello(
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val syncSpaceId: String,
    val deviceId: String,
    val ephemeralPublicKey: String,
    val nonce: String,
)

data class SyncHandshakeTranscript(
    val initiator: SyncHandshakeHello,
    val responder: SyncHandshakeHello,
    val staticIdentityKeys: List<String>,
)

@Serializable
private data class SyncHandshakeTranscriptWire(
    val initiator: SyncHandshakeHello,
    val responder: SyncHandshakeHello,
    val staticIdentityKeys: List<String>,
)

/** Discovery never authorizes a peer; users confirm this transcript-derived SAS/fingerprint first. */
object SyncPairing {
    private val json = Json { encodeDefaults = true }

    fun transcriptHash(transcript: SyncHandshakeTranscript): String {
        val canonical =
            json.encodeToString(
                SyncHandshakeTranscriptWire(
                    transcript.initiator,
                    transcript.responder,
                    transcript.staticIdentityKeys.sorted(),
                ),
            )
        return sha256Hex("ORIGREAD_SYNC_HANDSHAKE_V1\n$canonical")
    }

    fun sasCode(transcript: SyncHandshakeTranscript): String {
        val digest = transcriptHash(transcript)
        return "${digest.substring(0, 4)}-${digest.substring(4, 8)}-${digest.substring(8, 12)}".uppercase()
    }

    fun deviceFingerprint(staticIdentityKeySpkiBase64: String): String =
        sha256Hex(android.util.Base64.decode(staticIdentityKeySpkiBase64, android.util.Base64.DEFAULT))
            .chunked(4)
            .take(8)
            .joinToString(":")
            .uppercase()

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }
}
