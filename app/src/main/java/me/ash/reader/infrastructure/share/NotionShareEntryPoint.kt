package me.ash.reader.infrastructure.share

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotionShareEntryPoint {
    fun notionShareRepository(): NotionShareRepository
}
