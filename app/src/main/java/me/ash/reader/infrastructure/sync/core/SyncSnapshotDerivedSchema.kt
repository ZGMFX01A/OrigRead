package me.ash.reader.infrastructure.sync.core

/** P3 派生表示只保存字段因果承诺与父子关系，原始值继续按固定记录键定位。 */
internal val PAGED_SNAPSHOT_DERIVED_SCHEMA = listOf(
    """CREATE TABLE IF NOT EXISTS sync_snapshot_field_index(
        snapshot_bundle_id TEXT NOT NULL,replication_lane_id TEXT NOT NULL,record_key TEXT NOT NULL,
        version_token TEXT NOT NULL,logical_clock INTEGER NOT NULL,causal_context_json TEXT,
        value_digest TEXT NOT NULL,preference_value_json TEXT NOT NULL,
        PRIMARY KEY(snapshot_bundle_id,replication_lane_id,record_key))""",
    """CREATE INDEX IF NOT EXISTS sync_snapshot_field_source_order ON sync_snapshot_field_index
        (snapshot_bundle_id,replication_lane_id,version_token,record_key)""",
    """CREATE TABLE IF NOT EXISTS sync_snapshot_entity_index(
        snapshot_bundle_id TEXT NOT NULL,replication_lane_id TEXT NOT NULL,record_key TEXT NOT NULL,
        context_json TEXT NOT NULL,PRIMARY KEY(snapshot_bundle_id,replication_lane_id,record_key))""",
    """CREATE TABLE IF NOT EXISTS sync_snapshot_entity_edge(
        snapshot_bundle_id TEXT NOT NULL,replication_lane_id TEXT NOT NULL,record_key TEXT NOT NULL,
        ordinal INTEGER NOT NULL,parent_type TEXT NOT NULL,parent_id TEXT NOT NULL,parent_generation INTEGER,
        PRIMARY KEY(snapshot_bundle_id,replication_lane_id,record_key,ordinal))""",
    """CREATE TABLE IF NOT EXISTS sync_snapshot_source_proof(source_key TEXT NOT NULL,public_key_digest TEXT NOT NULL,
        validator_version INTEGER NOT NULL,PRIMARY KEY(source_key,public_key_digest,validator_version))""",
    """CREATE TRIGGER IF NOT EXISTS sync_snapshot_source_proof_delete AFTER DELETE ON sync_snapshot_source
        BEGIN DELETE FROM sync_snapshot_source_proof WHERE source_key=OLD.source_key; END""",
    """CREATE TRIGGER IF NOT EXISTS sync_snapshot_source_proof_update AFTER UPDATE ON sync_snapshot_source
        BEGIN DELETE FROM sync_snapshot_source_proof WHERE source_key=OLD.source_key; END""",
    derivedDeletion("DELETE"), derivedDeletion("UPDATE OF record_json"),
)

/** 修订、复制与资源记账共用这份正式派生表清单。 */
internal val SNAPSHOT_DERIVED_TABLES = listOf("sync_snapshot_field_index", "sync_snapshot_entity_index", "sync_snapshot_entity_edge")

/** 原记录改变或删除时，同事务失效派生事实，规范化批次负责重建当前元数据。 */
private fun derivedDeletion(event: String): String {
    val name = if (event == "DELETE") "delete" else "update"
    return """CREATE TRIGGER IF NOT EXISTS sync_snapshot_record_derived_$name AFTER $event ON sync_paged_snapshot_record
        BEGIN
        DELETE FROM sync_snapshot_field_index WHERE snapshot_bundle_id=OLD.snapshot_bundle_id AND replication_lane_id=OLD.replication_lane_id AND record_key=OLD.record_key;
        DELETE FROM sync_snapshot_entity_index WHERE snapshot_bundle_id=OLD.snapshot_bundle_id AND replication_lane_id=OLD.replication_lane_id AND record_key=OLD.record_key;
        DELETE FROM sync_snapshot_entity_edge WHERE snapshot_bundle_id=OLD.snapshot_bundle_id AND replication_lane_id=OLD.replication_lane_id AND record_key=OLD.record_key;
        END"""
}
