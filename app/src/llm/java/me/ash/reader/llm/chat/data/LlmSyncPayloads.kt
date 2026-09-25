package me.ash.reader.llm.chat.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.ash.reader.infrastructure.sync.core.SyncBlobDurability
import me.ash.reader.infrastructure.sync.core.SyncBlobPayloadCodec
import me.ash.reader.infrastructure.sync.core.SyncLocalBlobStore
import me.ash.reader.infrastructure.sync.core.SyncPayloadBlobRefWire

internal fun LlmConversationEntity.toSyncPayloadJson(): String =
    buildJsonObject {
        put("id", id)
        put("title", title)
        put("providerId", providerId)
        put("model", model)
        put("skillId", skillId)
        articleId?.let { put("articleLocalId", it) }
        articleTitle?.let { put("articleTitle", it) }
        articleLink?.let { put("articleLink", it) }
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }.toString()

internal fun LlmMessageEntity.toSyncPayloadJson(): String =
    buildJsonObject {
        put("id", id)
        put("conversationLocalId", conversationId)
        put("role", role.name)
        put("content", content)
        requestTask?.let { put("requestTask", it.name) }
        put("reasoning", reasoning)
        put("status", status.name)
        put("errorMessage", errorMessage)
        put("historyActive", historyActive)
        webSearchStatus?.let { put("webSearchStatus", it.name) }
        webSearchQuery?.let { put("webSearchQuery", it) }
        webSearchProviderName?.let { put("webSearchProviderName", it) }
        webSearchErrorMessage?.let { put("webSearchErrorMessage", it) }
        promptTokens?.let { put("promptTokens", it) }
        completionTokens?.let { put("completionTokens", it) }
        durationMs?.let { put("durationMs", it) }
        put("tokenUsageEstimated", tokenUsageEstimated)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }.toString()

internal fun LlmToolCallEntity.toSyncPayloadJson(blobStore: SyncLocalBlobStore? = null): String =
    buildJsonObject {
        put("id", id)
        put("conversationLocalId", conversationId)
        put("assistantMessageLocalId", assistantMessageId)
        put("providerCallId", providerCallId)
        put("toolId", toolId)
        toolName?.let { put("toolName", it) }
        toolSourceId?.let { put("toolSourceId", it) }
        put("apiName", apiName)
        put("argumentsJson", argumentsJson)
        put("status", status.name)
        resultContent?.let { result ->
            if (blobStore == null) {
                put("resultContent", result)
            } else {
                val ref = durableTextRef("resultContent", "tool_result", result)
                blobStore.putUtf8Text(ref, result)
                put("blobRefs", refsJson(listOf(ref)))
            }
        }
        errorMessage?.let { put("errorMessage", it) }
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }.toString()

internal fun LlmConversationArticleEntity.toSyncPayloadJson(blobStore: SyncLocalBlobStore): String {
    val original = durableTextRef("originalContent", "conversation_article_original", originalContent)
    blobStore.putUtf8Text(original, originalContent)
    return buildJsonObject {
        put("conversationLocalId", conversationId)
        put("articleLocalId", articleId)
        put("title", title)
        link?.let { put("link", it) }
        summary?.let { put("summary", it) }
        put("position", position)
        put("createdAt", createdAt)
        put("blobRefs", refsJson(listOf(original)))
    }.toString()
}

internal fun LlmContextRefEntity.toSyncPayloadJson(blobStore: SyncLocalBlobStore): String {
    val content = durableTextRef("contentSnapshot", "context_snapshot", contentSnapshot)
    val prompt =
        promptContentSnapshot?.let {
            durableTextRef("promptContentSnapshot", "context_prompt_snapshot", it)
        }
    blobStore.putUtf8Text(content, contentSnapshot)
    if (prompt != null) blobStore.putUtf8Text(prompt, checkNotNull(promptContentSnapshot))
    return buildJsonObject {
        put("id", id)
        put("conversationLocalId", conversationId)
        put("assistantMessageLocalId", assistantMessageId)
        put("contextId", contextId)
        put("type", type.name)
        title?.let { put("title", it) }
        sourceId?.let { put("sourceId", it) }
        articleId?.let { put("articleLocalId", it) }
        sourceUrl?.let { put("sourceUrl", it) }
        put("contentSha256", contentSha256)
        put("priority", priority)
        put("includedInPrompt", includedInPrompt)
        put("truncatedInPrompt", truncatedInPrompt)
        citationIndex?.let { put("citationIndex", it) }
        put("createdAt", createdAt)
        put("blobRefs", refsJson(listOfNotNull(content, prompt)))
    }.toString()
}

internal fun LlmEvidenceBlockEntity.toSyncPayloadJson(blobStore: SyncLocalBlobStore): String {
    val text = durableTextRef("textSnapshot", "evidence_text", textSnapshot)
    blobStore.putUtf8Text(text, textSnapshot)
    return buildJsonObject {
        put("id", id)
        put("contextRefLocalId", contextRefId)
        put("stableLocatorKey", stableLocatorKey)
        put("kind", kind.name)
        put("ordinal", ordinal)
        put("normalizedSha256", normalizedSha256)
        put("locator", locator.toSyncJson())
        put("schemaVersion", schemaVersion)
        put("createdAt", createdAt)
        put("blobRefs", refsJson(listOf(text)))
    }.toString()
}

internal fun LlmCitationRefEntity.toSyncPayloadJson(blobStore: SyncLocalBlobStore): String {
    val quote = durableTextRef("quoteSnapshot", "citation_quote", quoteSnapshot)
    blobStore.putUtf8Text(quote, quoteSnapshot)
    return buildJsonObject {
        put("id", id)
        put("conversationLocalId", conversationId)
        put("assistantMessageLocalId", assistantMessageId)
        put("contextRefLocalId", contextRefId)
        evidenceBlockId?.let { put("evidenceBlockLocalId", it) }
        put("targetKind", targetKind.name)
        put("protocolId", protocolId)
        displayOrder?.let { put("displayOrder", it) }
        sourceUrl?.let { put("sourceUrl", it) }
        locatorSnapshot?.let { put("locator", it.toSyncJson()) }
        put("schemaVersion", schemaVersion)
        put("createdAt", createdAt)
        put("blobRefs", refsJson(listOf(quote)))
    }.toString()
}

internal fun LlmCitationAnnotationEntity.toSyncPayloadJson(): String =
    buildJsonObject {
        put("id", id)
        put("conversationLocalId", conversationId)
        put("assistantMessageLocalId", assistantMessageId)
        put("canonicalInsertionOffset", canonicalInsertionOffset)
        put("occurrenceOrdinal", occurrenceOrdinal)
        put("schemaVersion", schemaVersion)
        put("createdAt", createdAt)
    }.toString()

internal fun LlmCitationAnnotationRefEntity.toSyncPayloadJson(): String =
    buildJsonObject {
        put("annotationLocalId", annotationId)
        put("citationRefLocalId", citationRefId)
        put("refOrdinal", refOrdinal)
    }.toString()

internal fun syncDeletePayloadJson(localId: String): String =
    buildJsonObject { put("localId", localId) }.toString()

internal fun syncFieldPayloadJson(field: String, value: String?): String =
    buildJsonObject { put("field", field); put("value", value) }.toString()

internal fun syncFieldPayloadJson(field: String, value: Boolean): String =
    buildJsonObject {
        put("field", field)
        put("value", value)
    }.toString()

private fun durableTextRef(
    field: String,
    referenceKind: String,
    text: String,
): SyncPayloadBlobRefWire =
    SyncBlobPayloadCodec.utf8TextReference(
        field = field,
        referenceKind = referenceKind,
        text = text,
        durability = SyncBlobDurability.SYNC_DURABLE,
    )

private fun refsJson(refs: List<SyncPayloadBlobRefWire>): JsonArray =
    buildJsonArray { refs.forEach { add(it.toJson()) } }

private fun SyncPayloadBlobRefWire.toJson(): JsonObject =
    buildJsonObject {
        put("field", field)
        put("referenceKind", referenceKind)
        putJsonObject("manifest") {
            put("hash", manifest.hash)
            put("totalBytes", manifest.totalBytes)
            manifest.mediaType?.let { put("mediaType", it) }
            manifest.compression?.let { put("compression", it) }
            manifest.encryptionInfoJson?.let { put("encryptionInfoJson", it) }
            put("availabilityPolicy", manifest.availabilityPolicy)
            put("durability", manifest.durability.name)
        }
    }

private fun LlmEvidenceLocatorV1.toSyncJson(): JsonObject =
    buildJsonObject {
        put("version", version)
        put("sourceKind", sourceKind.name)
        stableLocatorKey?.let { put("stableLocatorKey", it) }
        blockIndex?.let { put("blockIndex", it) }
        headingPath?.let { path ->
            put("headingPath", buildJsonArray { path.forEach { add(it) } })
        }
        articleId?.let { put("articleLocalId", it) }
        sourceUrl?.let { put("sourceUrl", it) }
        toolCallId?.let { put("toolCallLocalId", it) }
        toolId?.let { put("toolId", it) }
        toolName?.let { put("toolName", it) }
        toolSourceId?.let { put("toolSourceId", it) }
        put("normalizedHash", normalizedHash)
    }
