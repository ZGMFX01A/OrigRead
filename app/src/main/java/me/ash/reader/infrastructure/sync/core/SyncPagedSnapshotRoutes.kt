package me.ash.reader.infrastructure.sync.core

import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 仅在 listener 已完成 TLS、请求签名、兼容号及 transport peer 认证后处理分页路由。 */
@Singleton
class SyncPagedSnapshotRoutes @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val exporter: SyncPagedSnapshotExport,
) {
    @Inject lateinit var validation: SyncPagedInstallValidation
    @Inject lateinit var journal: SyncPagedSnapshotJournal
    @Inject lateinit var installer: SyncPagedSnapshotInstaller
    @Inject lateinit var policy: SyncLocalLanePolicy
    @Inject lateinit var jobs: SyncSnapshotJobs
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    data class Request(val space: String, val peer: String, val method: String, val segments: List<String>, val query: String?, val body: String)
    data class Response(val status: Int, val body: String)

    /** 串行保护 receive/commit 的状态转换，重复清单不得重置正在安装的真实进度。 */
    suspend fun handle(request: Request): Response {
        control(request)?.let { return it }
        return mutex.withLock { handleLocked(request) }
    }

    /** 控制入口不等待重型安装的路由锁，也不读取 Reader/Chat 数据。 */
    private fun control(request: Request): Response? {
        val action = request.segments.getOrNull(ACTION_SEGMENT)
        val bundle = request.segments[BUNDLE_SEGMENT]
        val status = when {
            request.method == "GET" && action == "job-status" -> jobs.status(request.space, request.peer, bundle)
            request.method == "POST" && action == "job-cancel" -> jobs.cancel(request.space, request.peer, bundle)
            else -> return null
        }
        return Response(200, json.encodeToString(status))
    }

    /** 接收小型状态转换仍串行，重型提交移交请求生命周期以外的执行器。 */
    private suspend fun handleLocked(request: Request): Response {
        val action = request.segments.getOrNull(ACTION_SEGMENT)
        if (request.method == "GET" && request.segments[BUNDLE_SEGMENT] == "latest") return latest(request)
        if (request.method == "PUT" && action == "manifest") return begin(request)
        val source = SyncPagedSnapshotJournal.Source(request.space, request.segments[BUNDLE_SEGMENT], request.peer)
        if (request.method == "GET" && request.segments.size == PAGE_SEGMENTS) return exportPage(request)
        val stage = journal.requireSource(source)
        val manifest = journal.manifest(source)
        validation.verifyAuthor(manifest)
        requirePolicy(manifest)
        return when {
            request.method == "GET" && action == "status" -> Response(200, json.encodeToString(store.pageStatus(manifest)))
            request.method == "PUT" && request.segments.size == PAGE_SEGMENTS -> receivePage(request, manifest)
            request.method == "POST" && action == "commit" -> {
                if (query(request.query)["jobs"] == "v1") {
                    val status = jobs.submit(SyncSnapshotJobInput(request.space, request.peer, manifest)) { commit(stage, manifest) }
                    Response(if (status.state == "COMPLETED") 200 else 202, json.encodeToString(status))
                } else commit(stage, manifest)
            }
            else -> error("REQUEST_ERROR: unknown paged Snapshot route")
        }
    }

    /** 每次查询重新读取政策，暂停 lane 不从另一个分页入口公开。 */
    private suspend fun latest(request: Request): Response {
        val query = query(request.query)
        val snapshotClass = query["class"]
        require(snapshotClass == null || snapshotClass in setOf("WORKING", "GC_BASELINE", "BOOTSTRAP_RECOVERY")) { "REQUEST_ERROR: invalid Snapshot class" }
        val device = checkNotNull(database.syncRuntimeDao().findDeviceIdentity())
        val bundle = database.syncGenesisDao().findLatestOwnedPagedBundle(request.space, device.deviceId, snapshotClass)
            ?: return Response(200, "null")
        val manifest = exporter.manifest(SyncPagedSnapshotExport.Scope(bundle.snapshotBundleId))
        val requested = query["lanes"]?.split(',')?.filter { it.isNotBlank() }?.toSet()
        val policy = policy.read(request.space)
        val selected = manifest.lanes.map { it.replicationLaneId }.filter { enabled(policy[it]) && (requested == null || it in requested) }.toSet()
        if (!selected.containsAll(manifest.requiredCoreShardIds + listOf("AUTH", "CORE_META")) || requested?.any { it !in selected } == true) return Response(200, "null")
        val scoped = exporter.manifest(SyncPagedSnapshotExport.Scope(bundle.snapshotBundleId, selected))
        validation.verifyAuthor(scoped)
        return Response(200, json.encodeToString(scoped))
    }

    /** URL、签名清单和 transport peer 三者绑定同一不可变接收记录。 */
    private suspend fun begin(request: Request): Response {
        val manifest = json.decodeFromString<SyncPagedSnapshotManifest>(request.body)
        require(manifest.syncSpaceId == request.space && manifest.snapshotBundleId == request.segments[BUNDLE_SEGMENT]) {
            "SPACE_MISMATCH: paged Snapshot endpoint identity mismatch"
        }
        validation.verifyAuthor(manifest)
        requirePolicy(manifest)
        journal.begin(SyncPagedSnapshotJournal.Receive(manifest, request.peer, System.currentTimeMillis()))
        return Response(200, "{\"accepted\":true}")
    }

    /** GET 使用 variant ID 自己的页摘要，原始 source ID 只作为来源证明。 */
    private suspend fun exportPage(request: Request): Response {
        val manifest = exporter.manifest(SyncPagedSnapshotExport.Scope(request.segments[BUNDLE_SEGMENT]))
        require(manifest.syncSpaceId == request.space) { "SPACE_MISMATCH: Snapshot is outside requested Space" }
        val lane = request.segments[ACTION_SEGMENT]
        require(manifest.lanes.any { it.replicationLaneId == lane }) { "SNAPSHOT_CORRUPTED: page lane is not in manifest" }
        requirePolicy(manifest.copy(lanes = manifest.lanes.filter { it.replicationLaneId == lane }))
        validation.verifyAuthor(manifest)
        return Response(200, json.encodeToString(exporter.page(SyncPagedSnapshotExport.Page(manifest.snapshotBundleId, lane, index(request)))))
    }

    /** 页位置同时核对 URL 和正文，再验证摘要并持久化。 */
    private fun receivePage(request: Request, manifest: SyncPagedSnapshotManifest): Response {
        val page = json.decodeFromString<SyncSnapshotBytePage>(request.body)
        require(page.replicationLaneId == request.segments[ACTION_SEGMENT] && page.pageIndex == index(request)) {
            "SNAPSHOT_CORRUPTED: Snapshot page URL and body disagree"
        }
        store.receivePage(manifest, page)
        return Response(200, "{\"accepted\":true}")
    }

    /** 全部页面及记录关联验证后才安装；进程崩溃留下 COMMITTING 时使用原 journal 重入。 */
    private suspend fun commit(stage: SyncPagedSnapshotJournal.Stage, manifest: SyncPagedSnapshotManifest): Response {
        check(stage.state in setOf("RECEIVING", "COMMITTING", "READY")) { "SNAPSHOT_CONFLICT: invalid receive journal state" }
        val now = System.currentTimeMillis()
        store.verifyAndPublish(manifest, now)
        journal.save(stage.copy(state = "COMMITTING", updatedAt = now))
        val binding = checkNotNull(database.syncRuntimeDao().findBindingBySpace(stage.space))
        // 安装错误继续暴露；COMMITTING 和完整页均保留，下一次重入恢复 Reader/Chat 的真实 journal。
        installer.install(SyncPagedSnapshotInstaller.Options(binding.localAccountId, manifest, now))
        journal.save(stage.copy(state = "READY", updatedAt = System.currentTimeMillis()))
        return Response(200, "{\"accepted\":true}")
    }

    /** 验证 lane 当前政策，已有页面不能绕过后来的暂停或清理。 */
    private suspend fun requirePolicy(manifest: SyncPagedSnapshotManifest) {
        val current = policy.read(manifest.syncSpaceId)
        require(manifest.lanes.all { enabled(current[it.replicationLaneId]) }) { "LANE_POLICY_BLOCKED: Snapshot contains disabled lane" }
    }

    /** 页号文本必须规范且处于 Android Int 可表示范围，越界显式失败。 */
    private fun index(request: Request): Int {
        val value = request.segments[PAGE_INDEX_SEGMENT]
        require(value.matches(Regex("0|[1-9][0-9]*"))) { "REQUEST_ERROR: invalid Snapshot page index" }
        return value.toInt()
    }

    /** 查询键值只用于类与 lane 选择，URL 值不会拼接到 SQL。 */
    private fun query(value: String?): Map<String, String> = value.orEmpty().split('&').filter { it.isNotBlank() }.associate { part ->
        val fields = part.split('=', limit = QUERY_PARTS)
        URLDecoder.decode(fields.first(), Charsets.UTF_8.name()) to URLDecoder.decode(fields.getOrElse(1) { "" }, Charsets.UTF_8.name())
    }

    private fun enabled(value: String?): Boolean = value !in setOf("PAUSED", "UNSUPPORTED", "LOCAL_PURGE")

    companion object {
        /** 协议路径中的固定 bundle/action/page index 位置。 */
        private const val BUNDLE_SEGMENT = 4
        /** manifest、status、commit 或实际逻辑 lane 的路径位置。 */
        private const val ACTION_SEGMENT = 6
        /** 字节页序号在协议路径中的位置。 */
        private const val PAGE_INDEX_SEGMENT = 7
        /** 单页路径的固定分段数量。 */
        private const val PAGE_SEGMENTS = 8
        /** 查询值允许包含等号，只拆分键与值一次。 */
        private const val QUERY_PARTS = 2
    }
}
