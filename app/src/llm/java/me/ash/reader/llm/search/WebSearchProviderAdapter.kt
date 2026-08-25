package me.ash.reader.llm.search

/** Dedicated Search Provider 的统一适配器契约。 */
interface WebSearchProviderAdapter {
    val kind: WebSearchProviderKind

    suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse
}

