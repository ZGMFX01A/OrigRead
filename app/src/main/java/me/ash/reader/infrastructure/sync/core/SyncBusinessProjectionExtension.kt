package me.ash.reader.infrastructure.sync.core

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import java.io.File

/**
 * Edition-specific business projections plug into the transport-independent R10 apply boundary.
 *
 * Standard has an empty set. LLM contributes AI_HISTORY without making main depend on llm source-set classes.
 */
interface SyncBusinessProjectionExtension {
    fun owns(entityType: String): Boolean

    fun canApplyWithoutBlob(entityType: String, referenceKind: String): Boolean = false

    suspend fun apply(operation: SyncOperationEntity)

    fun readLocalBlob(hash: String): ByteArray? = null

    fun localBlobFile(hash: String): File? = null

    suspend fun persistFetchedBlob(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        referenceKind: String,
        manifest: SyncBlobManifestWire,
        bytes: ByteArray,
    ) = Unit

    suspend fun prepareGenesis(
        syncSpaceId: String,
        genesisBaselineId: String,
        now: Long,
    ): List<SyncGenesisProjectionEntity> = emptyList()

    suspend fun genesisLaneFrontiers(
        syncSpaceId: String,
        crossDbCutId: String,
        actorIncarnationIds: Set<String>,
        capturedAt: Long,
    ): Map<String, Map<String, Long>> = emptyMap()

    suspend fun markGenesisIncluded(
        syncSpaceId: String,
        laneFrontiers: Map<String, Map<String, Long>>,
        now: Long,
    ) = Unit

    suspend fun buildPendingOperations(
        syncSpaceId: String,
        limit: Int,
        now: Long,
    ): Int = 0

    suspend fun hasPendingOutbox(syncSpaceId: String): Boolean = false

    suspend fun compactStableCoverage(
        syncSpaceId: String,
        stableCoverage: SyncCoverage,
    ) = Unit

    /**
     * Validate an extension-owned Snapshot entity before the Reader database enters the
     * destructive baseline transaction. Cross-database projections use this to surface
     * LocalRecovery requirements while all local materialized state is still intact.
     */
    suspend fun validateSnapshotEntity(
        syncSpaceId: String,
        entity: SyncGenesisProjectionEntity,
    ) = Unit

    suspend fun validateSnapshotTombstone(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
    ) = Unit

    suspend fun materializeSnapshotEntity(
        syncSpaceId: String,
        entity: SyncGenesisProjectionEntity,
        now: Long,
    ): Boolean = false

    suspend fun materializeSnapshotTombstone(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
        versionToken: String,
        deletedAt: Long,
    ): Boolean = false
}

data class SyncGenesisProjectionEntity(
    val entityType: String,
    val entitySyncId: String,
    val generation: Long,
    val fieldsJson: String,
)

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBusinessProjectionExtensionModule {
    @Multibinds
    abstract fun extensions(): Set<SyncBusinessProjectionExtension>
}
