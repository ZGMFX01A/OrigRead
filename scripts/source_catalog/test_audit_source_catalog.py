from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import unittest

from scripts.source_catalog.audit_source_catalog import (
    ParsedFeed,
    ProbeResult,
    age_days,
    classify_failure,
    classify_success,
    extract_alternate_urls,
    health_score,
    normalized_host,
    parse_feed,
    recheck_ids,
    should_attempt_repair,
)


class SourceCatalogAuditTest(unittest.TestCase):
    def test_parse_rss_and_atom(self) -> None:
        rss = b"""<?xml version='1.0'?><rss version='2.0'><channel><title>Example RSS</title>
        <item><title>A</title><pubDate>Tue, 01 Sep 2026 12:00:00 GMT</pubDate></item>
        <item><title>B</title><pubDate>Mon, 31 Aug 2026 12:00:00 GMT</pubDate></item>
        </channel></rss>"""
        parsed = parse_feed(rss, "application/rss+xml")
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual("Example RSS", parsed.title)
        self.assertEqual(2, parsed.item_count)
        self.assertEqual("2026-09-01T12:00:00Z", parsed.latest_article_at)

        atom = b"""<feed xmlns='http://www.w3.org/2005/Atom'><title>Example Atom</title>
        <entry><title>A</title><updated>2026-08-30T12:30:00Z</updated></entry></feed>"""
        parsed_atom = parse_feed(atom, "application/atom+xml")
        self.assertIsNotNone(parsed_atom)
        assert parsed_atom is not None
        self.assertEqual(1, parsed_atom.item_count)
        self.assertEqual("2026-08-30T12:30:00Z", parsed_atom.latest_article_at)

    def test_html_is_not_mistaken_for_feed_and_alternate_is_discovered(self) -> None:
        html = b"""<html><head><link rel='alternate' type='application/rss+xml' href='/feed.xml'></head></html>"""
        self.assertIsNone(parse_feed(html, "text/html"))
        self.assertEqual(["https://example.com/feed.xml"], extract_alternate_urls(html, "https://example.com/article"))
        self.assertEqual("example.com", normalized_host("https://www.example.com/feed"))

    def test_classification_is_conservative(self) -> None:
        fresh = ParsedFeed("Fresh", 12, "2026-09-01T00:00:00Z")
        stale = ParsedFeed("Old", 12, "2024-01-01T00:00:00Z")
        now = datetime(2026, 9, 2, tzinfo=timezone.utc)
        fresh_age = age_days(fresh.latest_article_at, now)
        stale_age = age_days(stale.latest_article_at, now)
        self.assertEqual("HEALTHY", classify_success(fresh, fresh_age, 365)[0])
        self.assertEqual("STALE", classify_success(stale, stale_age, 365)[0])
        self.assertEqual("LOW_QUALITY", classify_success(ParsedFeed("Empty", 0, None), None, 365)[0])

        self.assertEqual("BLOCKED", classify_failure(ProbeResult("u", False, status=403))[0])
        self.assertEqual("DEAD", classify_failure(ProbeResult("u", False, status=410))[0])
        self.assertEqual("TEMP_ERROR", classify_failure(ProbeResult("u", False, status=503))[0])
        self.assertEqual("TEMP_ERROR", classify_failure(ProbeResult("u", False, error_kind="TIMEOUT", error="timeout"))[0])
        self.assertEqual("BROKEN", classify_failure(ProbeResult("u", True, status=200))[0])
        self.assertTrue(should_attempt_repair(ProbeResult("u", True, status=200)))
        self.assertTrue(should_attempt_repair(ProbeResult("u", False, status=404)))
        self.assertFalse(should_attempt_repair(ProbeResult("u", False, status=403)))
        self.assertFalse(should_attempt_repair(ProbeResult("u", False, status=503)))
        self.assertFalse(should_attempt_repair(ProbeResult("u", False, error_kind="TIMEOUT")))

    def test_health_score_rewards_parse_recency_and_items(self) -> None:
        score = health_score(
            probe=ProbeResult("https://example.com/feed", True, status=200, elapsed_ms=500),
            parsed=ParsedFeed("Example", 20, "2026-09-01T00:00:00Z"),
            age=1,
            stale_days=365,
            resolved_url="https://example.com/feed",
            original_url="https://example.com/feed",
        )
        self.assertEqual(100, score)

    def test_recheck_report_selects_only_requested_classifications(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text(
                json.dumps({
                    "feeds": [
                        {"id": "a", "classification": "HEALTHY"},
                        {"id": "b", "classification": "TEMP_ERROR"},
                        {"id": "c", "classification": "DEAD"},
                    ]
                }),
                encoding="utf-8",
            )
            self.assertEqual({"b", "c"}, recheck_ids(path, "TEMP_ERROR,DEAD"))


if __name__ == "__main__":
    unittest.main()
