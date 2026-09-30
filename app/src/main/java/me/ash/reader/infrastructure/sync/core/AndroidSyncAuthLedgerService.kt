package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import me.ash.reader.infrastructure.db.AndroidDatabase

data class SyncAuthBootstrapPin(
    val deviceId: String,
    val publicKeySpkiBase64: String,
)

/**
 * Android 端 AUTH Ledger 存储、链式校验与持久化服务。
 *
 * 遵循 R10-R13 规范：
 * 1. 提供 GET / POST /v1/spaces/{spaceId}/auth/ledger 读写能力；
 * 2. 对每个写入的 AUTH 对象执行严格验证：结构合法性、author 签名验签、OWNER 链完整性与 epoch 单调性；
 * 3. 持久化后自动更新内存 Peer 授权状态与公钥缓存。
 */
@Singleton
class AndroidSyncAuthLedgerService @Inject constructor(
    private val database: AndroidDatabase,
    private val signingKeys: SyncDeviceSigningKeyStore,
    private val remoteApply: SyncRemoteApplyCoordinator,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun issueStabilityCheckpoint(
        syncSpaceId: String,
        now: Long = System.currentTimeMillis(),
        acceptedSnapshotBundleId: String? = null,
        verifiedExternalCoverage: SyncCoverage? = null,
    ): SyncAuthProtocolObject? =
        database.withTransaction {
            require(verifiedExternalCoverage == null || acceptedSnapshotBundleId != null) {
                "AUTH_FAILED: external stable coverage requires an accepted Snapshot"
            }
            val history = getAuthLedger(syncSpaceId).objects
            val head = history.lastOrNull() ?: return@withTransaction null
            val device = database.syncRuntimeDao().findDeviceIdentity() ?: return@withTransaction null
            if (head.ownerDeviceId != device.deviceId) return@withTransaction null
            val previous = history.lastOrNull { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
                ?.let(::checkpointCoverage) ?: emptyMap()
            val accepted = previous.mapValues { it.value.toMutableMap() }.toMutableMap()
            database.syncInboxDao().listCoverage(syncSpaceId).forEach { row ->
                if (row.retainedPrefix > 0) {
                    val lane = accepted.getOrPut(row.replicationLaneId) { mutableMapOf() }
                    lane[row.actorIncarnationId] = maxOf(lane[row.actorIncarnationId] ?: 0L, row.retainedPrefix)
                }
            }
            verifiedExternalCoverage.orEmpty().forEach { (laneId, actors) ->
                val lane = accepted.getOrPut(laneId) { mutableMapOf() }
                actors.forEach { (actorId, prefix) ->
                    lane[actorId] = maxOf(lane[actorId] ?: 0L, prefix)
                }
            }
            for (op in database.syncOperationDao().listAllForRecovery(syncSpaceId)
                .sortedWith(compareBy({ it.replicationLaneId }, { it.actorIncarnationId }, { it.sequence }))) {
                if (op.buildStatus != SyncOperationBuildStatus.SIGNED.name || database.syncInboxDao().find(op.operationId)?.state == "REJECTED") continue
                val prefix = accepted[op.replicationLaneId]?.get(op.actorIncarnationId) ?: 0L
                if (op.sequence == prefix + 1L) accepted.getOrPut(op.replicationLaneId) { mutableMapOf() }[op.actorIncarnationId] = op.sequence
            }
            if (accepted == previous && acceptedSnapshotBundleId == null) return@withTransaction null
            val payloadJson =
                SyncOperationCanonicalizer.canonicalJson(
                    buildJsonObject {
                        put(
                            "acceptedPrefixByActorLane",
                            json.parseToJsonElement(json.encodeToString(accepted)),
                        )
                        acceptedSnapshotBundleId?.let { put("acceptedSnapshotBundleId", it) }
                    }.toString()
                )
            val checkpoint = SyncAuthWireCodec.sign(SyncAuthProtocolObject(
                authObjectId = "pending", syncSpaceId = syncSpaceId, authEpoch = head.authEpoch,
                authSequence = head.authSequence + 1L, objectType = SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT,
                authorDeviceId = device.deviceId, ownerDeviceId = device.deviceId,
                payloadJson = payloadJson,
                payloadHash = "pending", signingDigest = "pending", authorSignature = "pending"), signingKeys)
            appendAuthObjects(syncSpaceId, listOf(checkpoint), now)
            checkpoint
        }

    /**
     * 读取指定 Sync Space 的完整 AUTH ledger 分页。
     *
     * @param syncSpaceId 同步空间 ID
     * @return 包含当前 Space 所有 AUTH 历史的 [SyncAuthLedgerPage]
     */
    suspend fun getAuthLedger(syncSpaceId: String): SyncAuthLedgerPage {
        val rows = database.syncAuthLedgerDao().list(syncSpaceId)
        val objects = rows.map { SyncAuthWireCodec.decode(it.authObjectJson) }.sortedWith(AUTH_ORDER)
        val currentEpoch = objects.lastOrNull()?.authEpoch ?: 0L
        val currentOwner = objects.lastOrNull()?.ownerDeviceId
        val checkpoint = objects.findLast { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
        return SyncAuthLedgerPage(
            objects = objects,
            authEpoch = currentEpoch,
            ownerDeviceId = currentOwner,
            authStabilityCheckpointId = checkpoint?.authObjectId,
        )
    }

    /**
     * Create the immutable epoch-0 SPACE_ROOT for a newly created local Sync Space.
     *
     * Genesis establishes the local installation as owner before any tail Operation is built, so
     * production writers never need an implicit no-AUTH authorization mode.
     */
    suspend fun ensureLocalSpaceRoot(
        syncSpaceId: String,
        now: Long = System.currentTimeMillis(),
    ): SyncAuthProtocolObject {
        val existing = getAuthLedger(syncSpaceId).objects
        if (existing.isNotEmpty()) {
            return checkNotNull(existing.firstOrNull { it.objectType == SyncAuthObjectType.SPACE_ROOT }) {
                "AUTH ledger for $syncSpaceId has no SPACE_ROOT"
            }
        }

        val device = checkNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
            "Device identity must exist before creating SPACE_ROOT"
        }
        val publicKey = signingKeys.publicKeySpkiBase64(device.deviceId)
        val payloadJson =
            SyncOperationCanonicalizer.canonicalJson(
                buildJsonObject {
                    put("ownerPublicKeySpkiBase64", publicKey)
                    put("publicKeySpkiBase64", publicKey)
                    put("spaceRootPublicKey", publicKey)
                }.toString()
            )
        val signed =
            SyncAuthWireCodec.sign(
                SyncAuthProtocolObject(
                    authObjectId = "pending",
                    syncSpaceId = syncSpaceId,
                    authEpoch = 0L,
                    authSequence = 0L,
                    objectType = SyncAuthObjectType.SPACE_ROOT,
                    authorDeviceId = device.deviceId,
                    ownerDeviceId = device.deviceId,
                    payloadJson = payloadJson,
                    payloadHash = "pending",
                    signingDigest = "pending",
                    authorSignature = "pending",
                ),
                signingKeys,
            )
        appendAuthObjects(syncSpaceId, listOf(signed), now)
        return signed
    }

    /** 单元测试中若使用 mock database，可置为 false；生产环境严格为 true 保证原子提交 */
    internal var runInTransaction: Boolean = true

    /**
     * 接收并验证一批 AUTH 对象，通过严格校验后持久化并刷新内存映射。
     *
     * @param syncSpaceId 同步空间 ID
     * @param incomingObjects 待追加的 AUTH 对象列表
     * @param now 当前系统时间戳（毫秒）
     * @return 更新后的最新 [SyncAuthLedgerPage]
     */
    suspend fun appendAuthObjects(
        syncSpaceId: String,
        incomingObjects: List<SyncAuthProtocolObject>,
        now: Long = System.currentTimeMillis(),
        refreshPeerCache: Boolean = true,
        bootstrapPin: SyncAuthBootstrapPin? = null,
    ): SyncAuthLedgerPage {
        if (incomingObjects.isEmpty()) {
            val current = getAuthLedger(syncSpaceId)
            if (refreshPeerCache && !database.inTransaction()) {
                remoteApply.applyAuthLedger(syncSpaceId, current.objects)
            }
            return current
        }

        if (runInTransaction) {
            database.withTransaction {
                for (obj in incomingObjects.sortedWith(AUTH_ORDER)) {
                    appendSingleObject(syncSpaceId, obj, now, bootstrapPin)
                }
            }
        } else {
            for (obj in incomingObjects.sortedWith(AUTH_ORDER)) {
                appendSingleObject(syncSpaceId, obj, now, bootstrapPin)
            }
        }

        // 重新查询最新 AUTH 记录并刷新内存中的 Peer 授权缓存
        val latestPage = getAuthLedger(syncSpaceId)
        if (refreshPeerCache && !database.inTransaction()) {
            remoteApply.applyAuthLedger(syncSpaceId, latestPage.objects)
        }
        return latestPage
    }

    /** Refresh the in-memory authorization cache only after the caller's outer Room transaction commits. */
    suspend fun refreshPeerAuthorizationCache(syncSpaceId: String) {
        check(!database.inTransaction()) { "AUTH peer cache cannot be published before the Room transaction commits" }
        remoteApply.applyAuthLedger(syncSpaceId, getAuthLedger(syncSpaceId).objects)
    }

    /**
     * 对单个 AUTH 对象进行幂等检查、签名验签、OWNER 链与 Epoch 规则校验并写入数据库。
     */
    private suspend fun appendSingleObject(
        syncSpaceId: String,
        obj: SyncAuthProtocolObject,
        now: Long,
        bootstrapPin: SyncAuthBootstrapPin? = null,
    ) {
        check(obj.syncSpaceId == syncSpaceId) {
            "AUTH object Sync Space (${obj.syncSpaceId}) does not match endpoint ($syncSpaceId)"
        }

        // 1. 结构与摘要规范性校验
        SyncAuthWireCodec.validate(obj)

        // 2. 幂等去重检查
        val existingRows = database.syncAuthLedgerDao().list(syncSpaceId)
        val existingObj = existingRows.firstOrNull { it.authObjectId == obj.authObjectId }
        if (existingObj != null) {
            val decoded = SyncAuthWireCodec.decode(existingObj.authObjectJson)
            check(SyncAuthWireCodec.encode(decoded) == SyncAuthWireCodec.encode(obj)) {
                "AUTH object identity collision with different content: ${obj.authObjectId}"
            }
            return
        }

        val existingObjects = existingRows.map { SyncAuthWireCodec.decode(it.authObjectJson) }.sortedWith(AUTH_ORDER)
        check(existingObjects.none { it.authEpoch == obj.authEpoch && it.authSequence == obj.authSequence }) {
            "AUTH_COLLISION: conflicting object at ${obj.authEpoch}/${obj.authSequence}"
        }
        check(existingObjects.isEmpty() || obj.objectType != SyncAuthObjectType.SPACE_ROOT) {
            "AUTH_FAILED: Space root is immutable"
        }

        // 3. 获取 author 的公钥并验证数字签名
        val authorPublicKey = resolveAuthorPublicKey(syncSpaceId, obj, existingObjects)
        checkNotNull(authorPublicKey) {
            "Cannot resolve public key for AUTH author: ${obj.authorDeviceId}"
        }
        val signatureValid = SyncAuthWireCodec.verify(obj, authorPublicKey, signingKeys)
        check(signatureValid) {
            "AUTH object signature verification failed for ${obj.authObjectId}"
        }
        if (existingObjects.isEmpty()) {
            val localDevice = database.syncRuntimeDao().findDeviceIdentity()
            val pinnedKey = when {
                bootstrapPin != null -> {
                    check(bootstrapPin.deviceId == obj.authorDeviceId && obj.ownerDeviceId == bootstrapPin.deviceId) {
                        "AUTH_FAILED: pairing bootstrap root does not belong to the confirmed peer"
                    }
                    bootstrapPin.publicKeySpkiBase64
                }
                localDevice?.deviceId == obj.authorDeviceId -> signingKeys.publicKeySpkiBase64(localDevice.deviceId)
                else -> remoteApply.trustedPeer(syncSpaceId, obj.authorDeviceId)?.publicKeySpkiBase64
            }
            check(pinnedKey != null && pinnedKey == authorPublicKey) { "AUTH_FAILED: initial root does not match a confirmed identity" }
        }

        // 4. OWNER 链及 Epoch 校验
        if (existingObjects.isEmpty()) {
            // 首个对象必须是 epoch 0 的 SPACE_ROOT，且 author 即 owner
            check(obj.objectType == SyncAuthObjectType.SPACE_ROOT) {
                "The first AUTH object must be SPACE_ROOT"
            }
            check(obj.authEpoch == 0L) {
                "The first AUTH object must start at epoch 0"
            }
            check(obj.authSequence == 0L) { "SPACE_ROOT must have authSequence 0" }
            check(obj.authorDeviceId == obj.ownerDeviceId) {
                "The first AUTH object author must be the initial owner"
            }
        } else {
            val current = existingObjects.last()
            val currentEpoch = current.authEpoch
            val currentOwner = current.ownerDeviceId

            val isOwnershipTransition =
                obj.objectType == SyncAuthObjectType.OWNER_TRANSFER ||
                    obj.objectType == SyncAuthObjectType.OWNER_RECOVERY

            if (isOwnershipTransition) {
                check(obj.authSequence == 0L) { "New owner epoch must start at authSequence 0" }
                check(obj.ownerDeviceId == obj.targetDeviceId) { "New owner must match targetDeviceId" }
                check(obj.authEpoch == currentEpoch + 1L) {
                    "Owner epoch must advance exactly once (expected ${currentEpoch + 1L}, got ${obj.authEpoch})"
                }
                if (obj.objectType == SyncAuthObjectType.OWNER_TRANSFER) {
                    check(obj.authorDeviceId == currentOwner) {
                        "Only the current owner ($currentOwner) can transfer ownership"
                    }
                    val payload = json.parseToJsonElement(obj.payloadJson).jsonObject
                    val targetKey = payload["publicKeySpkiBase64"]?.jsonPrimitive?.content
                    val acceptance = payload["ownerAcceptanceSignature"]?.jsonPrimitive?.content
                    check(!targetKey.isNullOrBlank() && !acceptance.isNullOrBlank()) { "OWNER_TRANSFER requires the new owner's signed acceptance" }
                    val material = SyncAuthWireCodec.ownerRecoverySigningMaterial(syncSpaceId, obj.authEpoch, obj.targetDeviceId.orEmpty(), obj.previousEpochFinalAcceptedPrefixByActorLane)
                        .replace("ORIGREAD_OWNER_RECOVERY_PROOF_V1", "ORIGREAD_OWNER_TRANSFER_ACCEPTANCE_V1")
                    check(signingKeys.verifyBase64(targetKey, material.toByteArray(Charsets.UTF_8), acceptance)) { "Invalid OWNER_TRANSFER acceptance" }
                }
                if (obj.objectType == SyncAuthObjectType.OWNER_RECOVERY) {
                    // 当非当前 Owner 发起恢复时，必须提供由 SpaceRootPublicKey 签发的 recoveryProof 数字签名
                    val payloadJson = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(obj.payloadJson) as? kotlinx.serialization.json.JsonObject
                    }.getOrNull()
                    val recoveryProof = payloadJson?.get("recoveryProof")?.let {
                        if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                    }
                    check(!recoveryProof.isNullOrBlank()) {
                        "Owner recovery requires a non-blank recoveryProof when the current owner is unavailable"
                    }

                    val spaceRoot = existingObjects.firstOrNull { it.objectType == SyncAuthObjectType.SPACE_ROOT }
                    checkNotNull(spaceRoot) { "Cannot find SPACE_ROOT in AUTH ledger for space $syncSpaceId" }

                    val spaceRootPayload = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(spaceRoot.payloadJson) as? kotlinx.serialization.json.JsonObject
                    }.getOrNull()
                    val declaredRootKey = spaceRootPayload?.get("spaceRootPublicKey")?.let {
                        if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                    }
                    val rootKey = declaredRootKey
                    checkNotNull(rootKey) { "Cannot resolve SpaceRoot public key for recovery validation" }

                    val valid = SyncAuthWireCodec.verifyOwnerRecoveryProof(
                        syncSpaceId = syncSpaceId,
                        authEpoch = obj.authEpoch,
                        targetDeviceId = obj.targetDeviceId.orEmpty(),
                        previousCut = obj.previousEpochFinalAcceptedPrefixByActorLane,
                        recoveryProofBase64 = recoveryProof,
                        spaceRootPublicKeySpkiBase64 = rootKey,
                        keyStore = signingKeys,
                    )
                    check(valid) {
                        "OWNER_RECOVERY proof signature verification failed for space $syncSpaceId"
                    }
                }
                check(!obj.targetDeviceId.isNullOrBlank()) {
                    "New owner targetDeviceId must be specified"
                }
            } else {
                check(current.authSequence != Long.MAX_VALUE && obj.authSequence == current.authSequence + 1L) {
                    "AUTH sequence must advance contiguously"
                }
                check(obj.authEpoch == currentEpoch) {
                    "AUTH epoch must remain $currentEpoch within the current owner reign"
                }
                check(obj.ownerDeviceId == currentOwner) {
                    "ownerDeviceId must remain $currentOwner"
                }
                check(obj.authorDeviceId == currentOwner) {
                    "Only the current owner ($currentOwner) may issue AUTH objects in the current epoch"
                }
            }
        }

        if (obj.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT) {
            val accepted = checkpointCoverage(obj)
            val previousStable = existingObjects
                .lastOrNull { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
                ?.let(::checkpointCoverage)
                ?: emptyMap()
            check(coverageDominates(accepted, previousStable)) {
                "AUTH stability checkpoint cannot move backwards"
            }
        }

        val cut = when (obj.objectType) {
            SyncAuthObjectType.MEMBER_REVOKE -> obj.revokeCutoffByActorLane ?: error("MEMBER_REVOKE requires a cutoff")
            SyncAuthObjectType.OWNER_TRANSFER, SyncAuthObjectType.OWNER_RECOVERY -> obj.previousEpochFinalAcceptedPrefixByActorLane
            else -> null
        }
        if (cut != null) {
            val stableCoverage = existingObjects
                .lastOrNull { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
                ?.let(::checkpointCoverage)
                ?: emptyMap()
            if (obj.objectType == SyncAuthObjectType.OWNER_TRANSFER ||
                obj.objectType == SyncAuthObjectType.OWNER_RECOVERY
            ) {
                check(coverageDominates(cut, stableCoverage)) {
                    "Owner epoch cut cannot cross an AuthStabilityCheckpoint"
                }
            }
            database.syncInboxDao().listCoverage(syncSpaceId).forEach { coverage ->
                if (obj.objectType == SyncAuthObjectType.MEMBER_REVOKE &&
                    !database.syncOperationDao().hasActorAuthor(syncSpaceId, coverage.actorIncarnationId, obj.targetDeviceId.orEmpty())) return@forEach
                check((cut[coverage.replicationLaneId]?.get(coverage.actorIncarnationId) ?: 0L) >= coverage.stableGcPrefix) {
                    "AUTH cutoff cannot revoke stable history"
                }
                val authStablePrefix =
                    stableCoverage[coverage.replicationLaneId]?.get(coverage.actorIncarnationId) ?: 0L
                check((cut[coverage.replicationLaneId]?.get(coverage.actorIncarnationId) ?: 0L) >= authStablePrefix) {
                    "AUTH cutoff cannot cross an AuthStabilityCheckpoint"
                }
            }
        }
        when (obj.objectType) {
            SyncAuthObjectType.MEMBER_REVOKE -> {
                val target = checkNotNull(obj.targetDeviceId) { "MEMBER_REVOKE requires targetDeviceId" }
                remoteApply.applyRevocationRollback(
                    syncSpaceId = syncSpaceId,
                    targetDeviceId = target,
                    revokeCutoffByActorLane = checkNotNull(obj.revokeCutoffByActorLane),
                    now = now,
                )
            }
            SyncAuthObjectType.OWNER_TRANSFER,
            SyncAuthObjectType.OWNER_RECOVERY,
            -> {
                remoteApply.applyEpochTransitionRollback(
                    syncSpaceId = syncSpaceId,
                    previousEpochFinalAcceptedPrefixByActorLane = obj.previousEpochFinalAcceptedPrefixByActorLane,
                    newAuthEpoch = obj.authEpoch,
                    now = now,
                )
            }
            else -> Unit
        }
        // 5. 校验通过，持久化写入数据库
        val entity = SyncAuthLedgerEntity(
            authObjectId = obj.authObjectId,
            syncSpaceId = syncSpaceId,
            authEpoch = obj.authEpoch,
            authObjectJson = SyncAuthWireCodec.encode(obj),
            updatedAt = now,
        )
        database.syncAuthLedgerDao().upsertAll(listOf(entity))
        syncDurableTrustFromAuthObject(syncSpaceId, obj, now)
        if (obj.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT) {
            remoteApply.promoteStableAuthorization(syncSpaceId, obj)
        }
    }

    private suspend fun syncDurableTrustFromAuthObject(
        syncSpaceId: String,
        obj: SyncAuthProtocolObject,
        now: Long,
    ) {
        val deviceId =
            when (obj.objectType) {
                SyncAuthObjectType.SPACE_ROOT -> obj.ownerDeviceId
                SyncAuthObjectType.MEMBER_GRANT,
                SyncAuthObjectType.MEMBER_REVOKE,
                SyncAuthObjectType.OWNER_TRANSFER,
                SyncAuthObjectType.OWNER_RECOVERY,
                -> obj.targetDeviceId
                SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT -> null
            } ?: return
        val trusted = database.syncTrustedDeviceDao().find(syncSpaceId, deviceId) ?: return
        val nextState =
            if (obj.objectType == SyncAuthObjectType.MEMBER_REVOKE) "REVOKED" else "TRUSTED"
        if (trusted.trustState != nextState || trusted.authEpoch != obj.authEpoch) {
            database.syncTrustedDeviceDao().updateTrustState(
                syncSpaceId = syncSpaceId,
                deviceId = deviceId,
                state = nextState,
                authEpoch = obj.authEpoch,
                lastSeenAt = now,
            )
        }
        if (nextState == "REVOKED") {
            val endpointId = "lan:$deviceId"
            database.syncEndpointDao().listAll()
                .firstOrNull { it.endpointId == endpointId && it.syncSpaceId == syncSpaceId && it.enabled }
                ?.let { endpoint ->
                    database.syncEndpointDao().upsert(
                        endpoint.copy(enabled = false, updatedAt = now)
                    )
                }
        }
    }

    private fun checkpointCoverage(obj: SyncAuthProtocolObject): SyncCoverage {
        check(obj.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT)
        val payload = json.parseToJsonElement(obj.payloadJson).jsonObject
        val accepted = payload["acceptedPrefixByActorLane"]?.jsonObject
            ?: error("AUTH stability checkpoint requires acceptedPrefixByActorLane")
        return accepted.mapValues { (_, laneValue) ->
            laneValue.jsonObject.mapValues { (_, prefixValue) ->
                prefixValue.jsonPrimitive.content.toLong()
            }
        }
    }

    private fun coverageDominates(left: SyncCoverage, right: SyncCoverage): Boolean =
        right.all { (lane, actors) ->
            actors.all { (actor, prefix) -> (left[lane]?.get(actor) ?: 0L) >= prefix }
        }

    /**
     * 解析 AUTH 对象 author 的公钥。
     * 优先从已有的 SPACE_ROOT 或 MEMBER_GRANT 历史中查找；若是创世 SPACE_ROOT，则从 payload 中读取。
     */
    private suspend fun resolveAuthorPublicKey(
        syncSpaceId: String,
        obj: SyncAuthProtocolObject,
        existingObjects: List<SyncAuthProtocolObject>,
    ): String? {
        // 如果是 SPACE_ROOT，公钥通常存在于 payloadJson 中（例如 {"ownerPublicKeySpkiBase64": "..."}）
        if (obj.objectType == SyncAuthObjectType.SPACE_ROOT) {
            runCatching {
                val element = json.parseToJsonElement(obj.payloadJson)
                element.jsonObject["ownerPublicKeySpkiBase64"]?.jsonPrimitive?.content
                    ?: element.jsonObject["publicKeySpkiBase64"]?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }

        // 从现有 AUTH 历史反向查找最近对该 author 的 MEMBER_GRANT 或 SPACE_ROOT
        for (historic in existingObjects.reversed()) {
            if (historic.objectType in setOf(SyncAuthObjectType.MEMBER_GRANT, SyncAuthObjectType.OWNER_TRANSFER, SyncAuthObjectType.OWNER_RECOVERY) && historic.targetDeviceId == obj.authorDeviceId) {
                val key = runCatching {
                    json.parseToJsonElement(historic.payloadJson).jsonObject["publicKeySpkiBase64"]?.jsonPrimitive?.content
                }.getOrNull()
                if (!key.isNullOrBlank()) return key
            }
            if (historic.objectType == SyncAuthObjectType.SPACE_ROOT && historic.authorDeviceId == obj.authorDeviceId) {
                val key = runCatching {
                    val rootPayload = json.parseToJsonElement(historic.payloadJson)
                    rootPayload.jsonObject["ownerPublicKeySpkiBase64"]?.jsonPrimitive?.content
                        ?: rootPayload.jsonObject["publicKeySpkiBase64"]?.jsonPrimitive?.content
                }.getOrNull()
                if (!key.isNullOrBlank()) return key
            }
        }

        // 从本地设备自身查找（如果是本机所创）
        val localDevice = runCatching {
            database.syncRuntimeDao().findDeviceIdentity()
        }.getOrNull()
        if (localDevice != null && localDevice.deviceId == obj.authorDeviceId) {
            return runCatching { signingKeys.publicKeySpkiBase64(localDevice.deviceId) }.getOrNull()
        }

        return null
    }

    /**
     * 计算并返回指定设备在目标 Sync Space 下当前生效的活跃 AUTH Grant。
     *
     * @param syncSpaceId 同步空间 ID
     * @param deviceId 设备 ID
     * @return 若当前设备拥有合法活跃授权，返回 [ActiveAuthGrant]；若未授权或已被撤销，返回 null
     */
    suspend fun findActiveGrant(syncSpaceId: String, deviceId: String): ActiveAuthGrant? {
        val rows = database.syncAuthLedgerDao().list(syncSpaceId)
        val objects = rows.map { SyncAuthWireCodec.decode(it.authObjectJson) }
        return computeActiveGrant(objects, deviceId)
    }

    companion object {
        val AUTH_ORDER: Comparator<SyncAuthProtocolObject> =
            compareBy<SyncAuthProtocolObject> { it.authEpoch }.thenBy { it.authSequence }
        /**
         * 依据 R10-R13 AUTH 规范，从全量有序的 AUTH 协议对象历史中推导指定设备的生效 Grant。
         */
        fun computeActiveGrant(objects: List<SyncAuthProtocolObject>, deviceId: String): ActiveAuthGrant? {
            if (objects.isEmpty()) return null

            val sorted = objects.sortedWith(AUTH_ORDER)

            var currentOwner = ""
            var currentOwnerGrantId = ""
            var currentEpoch = 0L
            val activeGrants = mutableMapOf<String, Pair<String, Long>>()
            val revoked = mutableSetOf<String>()

            for (obj in sorted) {
                currentEpoch = maxOf(currentEpoch, obj.authEpoch)

                when (obj.objectType) {
                    SyncAuthObjectType.SPACE_ROOT -> {
                        currentOwner = obj.ownerDeviceId
                        currentOwnerGrantId = obj.authObjectId
                        activeGrants[obj.ownerDeviceId] = obj.authObjectId to obj.authEpoch
                        revoked.remove(obj.ownerDeviceId)
                    }
                    SyncAuthObjectType.OWNER_TRANSFER,
                    SyncAuthObjectType.OWNER_RECOVERY -> {
                        obj.targetDeviceId?.let {
                            currentOwner = it
                            currentOwnerGrantId = obj.authObjectId
                            activeGrants[it] = obj.authObjectId to obj.authEpoch
                            revoked.remove(it)
                        }
                    }
                    SyncAuthObjectType.MEMBER_GRANT -> {
                        obj.targetDeviceId?.let {
                            activeGrants[it] = obj.authObjectId to obj.authEpoch
                            revoked.remove(it)
                        }
                    }
                    SyncAuthObjectType.MEMBER_REVOKE -> {
                        obj.targetDeviceId?.let {
                            revoked.add(it)
                            activeGrants.remove(it)
                        }
                    }
                    SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT -> Unit
                }
            }

            if (revoked.contains(deviceId)) {
                return null
            }

            if (deviceId == currentOwner && currentOwnerGrantId.isNotBlank()) {
                return ActiveAuthGrant(
                    authGrantId = currentOwnerGrantId,
                    authEpoch = currentEpoch,
                    isOwner = true,
                )
            }

            val memberGrant = activeGrants[deviceId]
            if (memberGrant != null) {
                return ActiveAuthGrant(
                    authGrantId = memberGrant.first,
                    authEpoch = currentEpoch,
                    isOwner = false,
                )
            }

            return null
        }
    }
}

data class ActiveAuthGrant(
    val authGrantId: String,
    val authEpoch: Long,
    val isOwner: Boolean,
)
