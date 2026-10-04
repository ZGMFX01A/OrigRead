package me.ash.reader.infrastructure.sync.core

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import java.io.File
import androidx.room.RoomDatabase

/**
 * Edition-specific business projections plug into the transport-independent R10 apply boundary.
 *
 * Standard has an empty set. LLM contributes AI_HISTORY without making main depend on llm source-set classes.
 */
interface SyncBusinessProjectionExtension {
    /** 围栏已发布后等待本库先前写入退出；实现只持有自己的短事务，不回调 Reader。 */
    suspend fun drainSnapshotWriters() = Unit
    /** 真实发布后清理所属库的原始 cut 与已关闭转换副本，不删除产品正文。 */
    suspend fun retireRawGenesis(cut: String) = Unit
    fun owns(entityType: String): Boolean

    /** 身份回填在取共同 cut 之前执行，冻结阶段只复制 SQL 原始列。 */
    suspend fun prepareRawGenesis(space: String, now: Long) = Unit

    /** 对所属数据库单独冻结，与 Reader 共享逻辑屏障但不嵌套写事务。 */
    suspend fun freezeRawGenesis(cut: SyncGenesisCut) = Unit

    /** 不完整的跨库 cut 不能继续从活库补另一半。 */
    fun rawGenesisReady(cut: String): Boolean = true

    /** 转换副本必须由所属 Edition 创建，Main 不依赖 Chat 实现。 */
    suspend fun openFrozenGenesis(cut: String, progress: SyncSourceCopyProgress? = null): Pair<String, RoomDatabase>? = null

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

    /** 分页捕获逐条消费正式投影，扩展不得用旧全库列表偷偷代替流式实现。 */
    suspend fun streamGenesis(options: SyncProjectionGenesisStream) {
        throw UnsupportedOperationException("Projection does not support paged Genesis capture")
    }

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

    /** 分页发布按真实 cut 更新 inclusion，不读取扩展拥有的整库 Outbox 正文。 */
    suspend fun markPagedGenesisIncluded(options: SyncProjectionGenesisInclusion) {
        throw UnsupportedOperationException("Projection does not support paged Genesis inclusion")
    }

    suspend fun buildPendingOperations(
        syncSpaceId: String,
        limit: Int,
        now: Long,
    ): Int = 0

    suspend fun hasPendingOutbox(syncSpaceId: String): Boolean = false

    /** 入组基线由所属数据库分配 Dot，避免 Reader/Chat 为同一 AI lane 分配冲突序列。 */
    suspend fun captureJoinBaseline(input: SyncJoinBaselineInput) {
        throw UnsupportedOperationException("Projection does not support Space join baseline capture")
    }

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

    /** 分页安装使用不可变 bundle/root 和当前记录身份，扩展不得伪造 Operation。 */
    suspend fun materializePagedEntity(options: SyncPagedProjectionEntity): Boolean {
        throw UnsupportedOperationException("Projection does not support paged Snapshot installation")
    }

    /** 安装完成必须核对当前 bundle 的正文义务，调用方持有投影屏障且不占 Reader 事务。 */
    suspend fun requirePagedBodies(syncSpaceId: String, snapshotBundleId: String) = Unit

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

/** 每个跨库完成记录只绑定当前不可变实体，不保存整 lane 正文。 */
data class SyncPagedProjectionEntity(val space: String, val bundleId: String, val rootHash: String,
    val recordKey: String, val entity: SyncGenesisProjectionEntity, val now: Long)

/** Edition 投影共享既有 Genesis barrier，回调直接写入当前固定视图的记录索引。 */
data class SyncProjectionGenesisStream(
    val syncSpaceId: String,
    val genesisBaselineId: String,
    val now: Long,
    val deferBlobVerification: Boolean = false,
    val consume: suspend (SyncGenesisProjectionEntity) -> Unit,
)

/** 扩展只承认自己拥有的连续 writer cut，页面数量不参与覆盖度计算。 */
data class SyncProjectionGenesisInclusion(val syncSpaceId: String, val laneFrontiers: SyncCoverage, val now: Long)

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBusinessProjectionExtensionModule {
    @Multibinds
    abstract fun extensions(): Set<SyncBusinessProjectionExtension>
}
