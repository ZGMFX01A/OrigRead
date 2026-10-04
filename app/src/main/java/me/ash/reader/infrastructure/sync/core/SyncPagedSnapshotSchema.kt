package me.ash.reader.infrastructure.sync.core

/** 页面主键保留真实逻辑 lane，独立记录索引检查跨页业务关联。 */
internal const val PAGED_SNAPSHOT_STORE_SCHEMA = """
CREATE TABLE sync_paged_snapshot (snapshot_bundle_id TEXT PRIMARY KEY, sync_space_id TEXT NOT NULL,
 manifest_json TEXT NOT NULL,state TEXT NOT NULL,updated_at INTEGER NOT NULL);
CREATE TABLE sync_paged_snapshot_page (snapshot_bundle_id TEXT NOT NULL,replication_lane_id TEXT NOT NULL,
 page_index INTEGER NOT NULL,content_hash TEXT NOT NULL,bytes BLOB NOT NULL,PRIMARY KEY(snapshot_bundle_id,replication_lane_id,page_index));
CREATE TABLE sync_paged_snapshot_record (snapshot_bundle_id TEXT NOT NULL,replication_lane_id TEXT NOT NULL,kind TEXT NOT NULL,
 record_key TEXT NOT NULL,content_hash TEXT NOT NULL,entity_type TEXT,entity_sync_id TEXT,generation INTEGER,field_id TEXT,blob_hash TEXT,
 record_json TEXT NOT NULL,PRIMARY KEY(snapshot_bundle_id,replication_lane_id,kind,record_key));
CREATE INDEX index_sync_paged_record_entity ON sync_paged_snapshot_record
 (snapshot_bundle_id,replication_lane_id,entity_type,entity_sync_id,generation,kind);
"""
