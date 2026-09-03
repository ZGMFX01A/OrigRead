import unittest

from scripts.source_catalog.apply_source_catalog_audit import (
    clean_catalog,
    normalized_classification,
    safe_moved_replacement,
)


class ApplySourceCatalogAuditTest(unittest.TestCase):
    def test_old_direct_redirect_moved_is_normalized(self):
        self.assertEqual("HEALTHY", normalized_classification({"classification": "MOVED", "repairMethod": None, "itemCount": 10, "ageDays": 2}))
        self.assertEqual("STALE", normalized_classification({"classification": "MOVED", "repairMethod": None, "itemCount": 10, "ageDays": 900}))

    def test_generic_feed_host_replacement_is_rejected(self):
        entry = {"feedUrl": "https://feeds.megaphone.fm/show-name", "siteUrl": None}
        record = {"classification": "MOVED", "resolvedFeedUrl": "https://feeds.megaphone.fm/feed", "repairMethod": "feed-origin-common-path"}
        self.assertIsNone(safe_moved_replacement(entry, record))

    def test_same_site_replacement_is_allowed(self):
        entry = {"feedUrl": "https://example.com/feed.xml", "siteUrl": "https://example.com"}
        record = {"classification": "MOVED", "resolvedFeedUrl": "https://example.com/rss.xml", "repairMethod": "feed-origin-common-path"}
        self.assertEqual("https://example.com/rss.xml", safe_moved_replacement(entry, record))

    def test_cleanup_deletes_low_value_and_keeps_blocked(self):
        catalog = {
            "schemaVersion": 1,
            "generatedAt": None,
            "feedCount": 3,
            "categories": ["Tech"],
            "sources": [],
            "feeds": [
                {"id": "a", "name": "A", "feedUrl": "https://a.example/feed", "siteUrl": None, "categories": ["Tech"], "origins": []},
                {"id": "b", "name": "B", "feedUrl": "https://b.example/feed", "siteUrl": None, "categories": ["Tech"], "origins": []},
                {"id": "c", "name": "C", "feedUrl": "https://c.example/feed", "siteUrl": None, "categories": ["Tech"], "origins": []},
            ],
        }
        report = [
            {"id": "a", "classification": "STALE"},
            {"id": "b", "classification": "BLOCKED"},
            {"id": "c", "classification": "HEALTHY"},
        ]
        cleaned, summary = clean_catalog(catalog, [report])
        self.assertEqual(2, cleaned["feedCount"])
        self.assertEqual(["B", "C"], [item["name"] for item in cleaned["feeds"]])
        self.assertEqual(1, summary["removedByClassification"]["STALE"])
        self.assertEqual(1, summary["keptBlocked"])


if __name__ == "__main__":
    unittest.main()
