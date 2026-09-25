package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

const val SYNC_ARTICLE_FULL_CONTENT_FIELD = "fullContentHtml"
const val SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND = "article_full_content"

object SyncBlobPayloadCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun references(payloadJson: String): List<SyncPayloadBlobRefWire> {
        val root = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return emptyList()
        val refs = root["blobRefs"] as? JsonArray ?: return emptyList()
        return runCatching {
            json.decodeFromJsonElement(ListSerializer(SyncPayloadBlobRefWire.serializer()), refs)
        }.getOrElse {
            throw IllegalArgumentException("Invalid Blob references in operation payload", it)
        }
    }

    fun utf8TextReference(
        field: String,
        referenceKind: String,
        text: String,
        durability: SyncBlobDurability,
        availabilityPolicy: String = "LAZY",
    ): SyncPayloadBlobRefWire {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return SyncPayloadBlobRefWire(
            field = field,
            referenceKind = referenceKind,
            manifest = SyncBlobManifestWire(
                hash = SyncOperationCanonicalizer.sha256Hex(text),
                totalBytes = bytes.size.toLong(),
                mediaType = "text/plain; charset=utf-8",
                availabilityPolicy = availabilityPolicy,
                durability = durability,
            ),
        )
    }

    fun articleFullContentReference(text: String): SyncPayloadBlobRefWire =
        utf8TextReference(
            field = SYNC_ARTICLE_FULL_CONTENT_FIELD,
            referenceKind = SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND,
            text = text,
            durability = SyncBlobDurability.REHYDRATABLE,
            availabilityPolicy = "LAZY",
        )
}
