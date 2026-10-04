package me.ash.reader.infrastructure.sync.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 页面、接收身份和本地恢复队列共用专用数据库，升级只新增表，不丢弃已接收内容。 */
internal class SyncPagedSnapshotDatabase(context: Context) :
    SQLiteOpenHelper(context, "sync-paged-snapshots.db", null, STORE_VERSION) {
    /** 首次创建完整分页协议存储。 */
    override fun onCreate(database: SQLiteDatabase) {
        PAGED_SNAPSHOT_STORE_SCHEMA.split(';').filter { it.isNotBlank() }.forEach { database.execSQL(it) }
        database.execSQL(PAGED_SNAPSHOT_JOURNAL_SCHEMA)
        database.execSQL(PAGED_SNAPSHOT_TAIL_SCHEMA)
        database.execSQL(PAGED_SNAPSHOT_CLEANUP_SCHEMA)
        PAGED_SNAPSHOT_SOURCE_SCHEMA.split(';').filter { it.isNotBlank() }.forEach { database.execSQL(it) }
        database.execSQL(PAGED_SNAPSHOT_BATCH_SCHEMA)
        PAGED_SNAPSHOT_DERIVED_SCHEMA.forEach(database::execSQL)
    }

    /** v1 页面、v2 接收 journal 均原样保留，v3 增加重放操作的持久化副本。 */
    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in FIRST_STORE_VERSION until STORE_VERSION && newVersion == STORE_VERSION) {
            "Snapshot store migration is missing: $oldVersion -> $newVersion"
        }
        if (oldVersion < JOURNAL_STORE_VERSION) database.execSQL(PAGED_SNAPSHOT_JOURNAL_SCHEMA)
        if (oldVersion < TAIL_STORE_VERSION) database.execSQL(PAGED_SNAPSHOT_TAIL_SCHEMA)
        if (oldVersion < CLEANUP_STORE_VERSION) database.execSQL(PAGED_SNAPSHOT_CLEANUP_SCHEMA)
        if (oldVersion < SOURCE_STORE_VERSION) PAGED_SNAPSHOT_SOURCE_SCHEMA.split(';').filter { it.isNotBlank() }.forEach { database.execSQL(it) }
        if (oldVersion < SOURCE_STORE_VERSION) database.execSQL(PAGED_SNAPSHOT_BATCH_SCHEMA)
        if (oldVersion < STORE_VERSION) PAGED_SNAPSHOT_DERIVED_SCHEMA.forEach(database::execSQL)
    }

    companion object {
        /** 原始页面/记录存储版本。 */
        private const val FIRST_STORE_VERSION = 1
        /** 接收身份 journal 引入版本。 */
        private const val JOURNAL_STORE_VERSION = 2
        /** 本地尾部操作副本引入版本。 */
        private const val TAIL_STORE_VERSION = 3
        /** 跨库级联清理拥有者表首次引入版本。 */
        private const val CLEANUP_STORE_VERSION = 4
        /** v5 仅增加本地来源池，v4 已有跨库级联正文清理计划。 */
        private const val SOURCE_STORE_VERSION = 5
        /** v6 新增可重建字段轻索引与实体关系索引，原始页和签名继续保留。 */
        private const val STORE_VERSION = 6
    }
}

/** 恢复队列保存完整操作而不是仅引用可能被 GC 删除的日志 ID。 */
internal const val PAGED_SNAPSHOT_TAIL_SCHEMA = """
CREATE TABLE sync_paged_snapshot_tail(snapshot_bundle_id TEXT NOT NULL,operation_id TEXT NOT NULL,
 replication_lane_id TEXT NOT NULL,actor_incarnation_id TEXT NOT NULL,sequence INTEGER NOT NULL,
 replay_required INTEGER NOT NULL,replayed INTEGER NOT NULL,operation_json TEXT NOT NULL,
 PRIMARY KEY(snapshot_bundle_id,operation_id),
 UNIQUE(snapshot_bundle_id,replication_lane_id,actor_incarnation_id,sequence))
"""

/** 只保存跨库级联删除的真实拥有者，巨型正文不进入完成 journal。 */
internal const val PAGED_SNAPSHOT_CLEANUP_SCHEMA = """
CREATE TABLE sync_snapshot_cleanup_owner(receipt_id TEXT NOT NULL,entity_type TEXT NOT NULL,entity_sync_id TEXT NOT NULL,
 generation INTEGER NOT NULL,PRIMARY KEY(receipt_id,entity_type,entity_sync_id,generation))
"""
