package me.ash.reader.infrastructure.sync.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SyncRollbackWitnessV1(
    val schemaVersion: Int = 1,
    val deviceId: String? = null,
    val deviceWitnessId: String? = null,
    val actorLaneHighWater: Map<String, Long> = emptyMap(),
)

interface SyncRollbackWitnessStore {
    fun snapshot(): SyncRollbackWitnessV1

    fun replaceDevice(deviceId: String, witnessId: String)

    fun highWater(actorIncarnationId: String, replicationLaneId: String): Long?

    fun laneHighWater(actorIncarnationId: String): Map<String, Long> {
        val prefix = "$actorIncarnationId|"
        return snapshot().actorLaneHighWater
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
    }

    /**
     * Persist the sequence before the SQLite transaction commits.
     *
     * If SQLite later rolls back/crashes, the witness intentionally stays ahead and forces a new Actor
     * Incarnation instead of allowing Dot reuse.
     */
    fun reserveSequence(actorIncarnationId: String, replicationLaneId: String, sequence: Long)
}

@Singleton
class AndroidSyncRollbackWitnessStore @Inject constructor(
    @ApplicationContext context: Context,
) : SyncRollbackWitnessStore {
    private val file = File(context.noBackupFilesDir, "origread-sync-rollback-witness-v1.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    override fun snapshot(): SyncRollbackWitnessV1 = synchronized(lock) { load() }

    override fun replaceDevice(deviceId: String, witnessId: String) {
        synchronized(lock) {
            val current = load()
            write(
                current.copy(
                    deviceId = deviceId,
                    deviceWitnessId = witnessId,
                )
            )
        }
    }

    override fun highWater(actorIncarnationId: String, replicationLaneId: String): Long? =
        synchronized(lock) {
            load().actorLaneHighWater[witnessKey(actorIncarnationId, replicationLaneId)]
        }

    override fun laneHighWater(actorIncarnationId: String): Map<String, Long> =
        synchronized(lock) {
            val prefix = "$actorIncarnationId|"
            load().actorLaneHighWater
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { (key, _) -> key.removePrefix(prefix) }
        }

    override fun reserveSequence(actorIncarnationId: String, replicationLaneId: String, sequence: Long) {
        require(sequence > 0) { "sequence must be positive" }
        synchronized(lock) {
            val current = load()
            val key = witnessKey(actorIncarnationId, replicationLaneId)
            val existing = current.actorLaneHighWater[key] ?: 0L
            check(sequence == existing + 1L) {
                "Non-contiguous rollback witness reservation for $key: existing=$existing requested=$sequence"
            }
            write(current.copy(actorLaneHighWater = current.actorLaneHighWater + (key to sequence)))
        }
    }

    private fun load(): SyncRollbackWitnessV1 =
        runCatching {
            if (!file.exists()) return@runCatching SyncRollbackWitnessV1()
            json.decodeFromString<SyncRollbackWitnessV1>(file.readText(Charsets.UTF_8))
        }.getOrDefault(SyncRollbackWitnessV1())

    private fun write(value: SyncRollbackWitnessV1) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(value), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun witnessKey(actorIncarnationId: String, replicationLaneId: String): String =
        "$actorIncarnationId|$replicationLaneId"
}
