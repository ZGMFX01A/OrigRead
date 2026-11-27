"""用标准库 SQLite 验证真实 Room SQL 与导出 schema；无需 Android 设备或第三方依赖。"""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
DAO = (ROOT / "app/src/llm/java/me/ash/reader/llm/chat/data/LlmChatDao.kt").read_text(encoding="utf-8")
SCHEMAS = ROOT / "app/schemas/me.ash.reader.llm.chat.data.LlmChatDatabase"


def query(method):
    end = DAO.index("suspend fun " + method + "(")
    text = DAO[DAO.rfind("@Query(", 0, end):end]
    return "".join(json.loads(token) for token in re.findall(r'"(?:[^"\\]|\\.)*"', text))


class ChatSqlRegressionTest(unittest.TestCase):
    def setUp(self):
        schema = max(SCHEMAS.glob("*.json"), key=lambda path: int(path.stem))
        self.entities = json.loads(schema.read_text())["database"]["entities"]
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys = ON")
        for entity in self.entities:
            self.db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity["indices"]:
                self.db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        self.insert("llm_conversations", id="c", title="original", updated_at=10)
        self.insert("llm_messages", id="m", conversation_id="c", content="old", history_active=0,
                    web_search_status="SUCCESS", updated_at=10)

    def tearDown(self):
        self.db.close()

    def insert(self, table, **values):
        entity = next(e for e in self.entities if e["tableName"] == table)
        fields = {f["columnName"]: (0 if f["affinity"] == "INTEGER" else "")
                  for f in entity["fields"] if f.get("notNull")}
        fields.update(values)
        names = ",".join('"' + name + '"' for name in fields)
        self.db.execute(f"INSERT INTO {table} ({names}) VALUES ({','.join('?' for _ in fields)})", list(fields.values()))

    def test_independent_conversation_updates_keep_both_columns_and_monotonic_time(self):
        self.db.execute(query("updateConversationRuntime"), dict(id="c", providerId="p", model="model", skillId="s", updatedAt=30))
        self.db.execute(query("renameConversation"), dict(id="c", title="renamed", updatedAt=20))
        self.db.execute(query("touchConversation"), dict(id="c", updatedAt=15))
        self.assertEqual(("renamed", "p", "model", "s", 30), self.db.execute(
            "SELECT title, provider_id, model, skill_id, updated_at FROM llm_conversations").fetchone())

    def test_generation_patch_preserves_other_owners_columns(self):
        sql = query("patchMessage")
        params = {key: None for key in re.findall(r":(\w+)", sql)}
        params.update({key: False for key in params if key.startswith("set")})
        params.update(id="m", content="answer", setContent=True, updatedAt=20)
        self.db.execute(sql, params)
        self.assertEqual(("answer", 0, "SUCCESS"), self.db.execute(
            "SELECT content, history_active, web_search_status FROM llm_messages").fetchone())

    def test_orphan_cleanup_keeps_other_messages_and_multi_source_annotations(self):
        self.insert("llm_messages", id="other", conversation_id="c")
        for suffix in ("one", "two"):
            self.insert("llm_context_refs", id=suffix, conversation_id="c", assistant_message_id="m", context_id=suffix)
            self.insert("llm_citation_refs", id=suffix, conversation_id="c", assistant_message_id="m", context_ref_id=suffix, protocol_id=suffix)
        self.insert("llm_citation_annotations", id="shared", conversation_id="c", assistant_message_id="m")
        self.insert("llm_citation_annotations", id="orphan", conversation_id="c", assistant_message_id="m", occurrence_ordinal=1)
        self.insert("llm_citation_annotations", id="other", conversation_id="c", assistant_message_id="other")
        for suffix in ("one", "two"):
            self.insert("llm_citation_annotation_refs", annotation_id="shared", citation_ref_id=suffix, ref_ordinal=0 if suffix == "one" else 1)
        self.db.execute("DELETE FROM llm_context_refs WHERE id = 'one'")
        self.db.execute(query("deleteOrphanCitationAnnotationsForAssistant"), dict(assistantMessageId="m"))
        self.assertEqual([("other",), ("shared",)], self.db.execute("SELECT id FROM llm_citation_annotations ORDER BY id").fetchall())
        self.db.execute(query("deleteCitationRefsForAssistant"), dict(assistantMessageId="m"))
        self.db.execute(query("deleteOrphanCitationAnnotationsForAssistant"), dict(assistantMessageId="m"))
        self.assertEqual([("other",)], self.db.execute("SELECT id FROM llm_citation_annotations").fetchall())


if __name__ == "__main__":
    unittest.main()
