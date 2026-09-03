#!/usr/bin/env python3
"""Apply vetted source-catalog audit results to Android and Desktop assets.

Policy is intentionally biased toward a smaller, useful built-in discovery catalog:

* keep HEALTHY and BLOCKED entries;
* remove STALE, LOW_QUALITY, BROKEN, DEAD and repeatedly failing TEMP_ERROR entries;
* update MOVED entries only when the replacement is plausibly tied to the original/site host;
* otherwise remove the unusable old MOVED entry instead of accepting a generic feed-host /feed;
* deduplicate after URL replacement and merge categories/origins.

Audit reports are applied in the order supplied; later reports override earlier ones.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import OrderedDict
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit


DELETE_CLASSIFICATIONS = {"STALE", "LOW_QUALITY", "BROKEN", "DEAD", "TEMP_ERROR"}
GENERIC_FEED_HOST_SUFFIXES = (
    "feedburner.com",
    "megaphone.fm",
    "simplecast.com",
    "acast.com",
)


def feed_key(url: str) -> str:
    parts = urlsplit(url.strip())
    host = parts.netloc.casefold()
    path = parts.path.rstrip("/") or "/"
    return urlunsplit(("", host, path, parts.query, ""))


def stable_id(url: str) -> str:
    return hashlib.sha256(feed_key(url).encode("utf-8")).hexdigest()[:20]


def normalized_host(url: str | None) -> str:
    if not url:
        return ""
    try:
        return (urlsplit(url).hostname or "").casefold().removeprefix("www.")
    except ValueError:
        return ""


def is_generic_feed_host(host: str) -> bool:
    return any(host == suffix or host.endswith("." + suffix) for suffix in GENERIC_FEED_HOST_SUFFIXES)


def normalized_classification(record: dict) -> str:
    classification = str(record.get("classification") or "").upper()
    # The first audit version labelled every successful HTTP redirect as MOVED. A true
    # migration candidate has repairMethod; a direct parse with no repair is simply a
    # healthy/stale/empty feed at its canonical redirect target.
    if classification == "MOVED" and not record.get("repairMethod"):
        if int(record.get("itemCount") or 0) == 0:
            return "LOW_QUALITY"
        age = record.get("ageDays")
        if isinstance(age, int) and age > 365:
            return "STALE"
        return "HEALTHY"
    return classification


def safe_moved_replacement(entry: dict, record: dict) -> str | None:
    if normalized_classification(record) != "MOVED":
        return None
    resolved = str(record.get("resolvedFeedUrl") or "").strip()
    if not resolved:
        return None
    old_host = normalized_host(entry.get("feedUrl"))
    site_host = normalized_host(entry.get("siteUrl"))
    new_host = normalized_host(resolved)
    if not new_host:
        return None
    # Never replace a dead named feed with a generic /feed exposed by a podcast/feed
    # hosting service. Those endpoints can be valid XML while representing unrelated data.
    if is_generic_feed_host(old_host) or is_generic_feed_host(new_host):
        return None
    if new_host == old_host:
        return resolved
    if site_host and (new_host == site_host or new_host.endswith("." + site_host) or site_host.endswith("." + new_host)):
        return resolved
    # Allow an explicit HTML-discovered replacement even when a publisher changed domains.
    method = str(record.get("repairMethod") or "")
    if "html-alternate" in method:
        return resolved
    return None


def merge_entry(target: dict, source: dict) -> None:
    for category in source.get("categories") or []:
        if category not in target["categories"]:
            target["categories"].append(category)
    for origin in source.get("origins") or []:
        if origin not in target["origins"]:
            target["origins"].append(origin)
    if not target.get("siteUrl") and source.get("siteUrl"):
        target["siteUrl"] = source["siteUrl"]


def load_report(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    feeds = data.get("feeds")
    if not isinstance(feeds, list):
        raise ValueError(f"Audit report has no feeds array: {path}")
    return feeds


def clean_catalog(catalog: dict, reports: list[list[dict]]) -> tuple[dict, dict]:
    latest: dict[str, dict] = {}
    for report in reports:
        for record in report:
            record_id = str(record.get("id") or "")
            if record_id:
                latest[record_id] = record

    kept: OrderedDict[str, dict] = OrderedDict()
    removed: list[dict] = []
    updated: list[dict] = []
    kept_blocked = 0

    for original in catalog["feeds"]:
        entry = {
            "id": original["id"],
            "name": original["name"],
            "feedUrl": original["feedUrl"],
            "siteUrl": original.get("siteUrl"),
            "categories": list(original.get("categories") or []),
            "origins": list(original.get("origins") or []),
        }
        record = latest.get(entry["id"])
        classification = normalized_classification(record) if record else "UNAUDITED"

        if classification == "BLOCKED":
            kept_blocked += 1
        if classification in DELETE_CLASSIFICATIONS:
            removed.append({"id": entry["id"], "name": entry["name"], "classification": classification})
            continue
        if classification == "MOVED":
            replacement = safe_moved_replacement(entry, record or {})
            if not replacement:
                removed.append({"id": entry["id"], "name": entry["name"], "classification": "MOVED_UNSAFE"})
                continue
            old_url = entry["feedUrl"]
            entry["feedUrl"] = replacement
            entry["id"] = stable_id(replacement)
            updated.append({"name": entry["name"], "oldFeedUrl": old_url, "newFeedUrl": replacement})

        key = feed_key(entry["feedUrl"])
        existing = kept.get(key)
        if existing is None:
            kept[key] = entry
        else:
            merge_entry(existing, entry)

    feeds = list(kept.values())
    for entry in feeds:
        entry["categories"] = sorted(set(entry["categories"]), key=str.casefold)
    feeds.sort(key=lambda item: item["name"].casefold())
    categories = sorted({category for entry in feeds for category in entry["categories"]}, key=str.casefold)
    cleaned = {
        **catalog,
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "feedCount": len(feeds),
        "categories": categories,
        "feeds": feeds,
    }
    summary = {
        "before": len(catalog["feeds"]),
        "after": len(feeds),
        "removed": len(catalog["feeds"]) - len(feeds),
        "updatedMoved": len(updated),
        "keptBlocked": kept_blocked,
        "removedByClassification": {},
        "updated": updated,
        "removedEntries": removed,
    }
    for item in removed:
        key = item["classification"]
        summary["removedByClassification"][key] = summary["removedByClassification"].get(key, 0) + 1
    summary["removedByClassification"] = dict(sorted(summary["removedByClassification"].items()))
    return cleaned, summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Apply source catalog audit results and sync Android/Desktop assets")
    parser.add_argument("--catalog", type=Path, default=Path("app/src/main/assets/source_catalog.json"))
    parser.add_argument("--report", type=Path, action="append", required=True, help="Audit report; later reports override earlier ones")
    parser.add_argument("--android-output", type=Path, default=Path("app/src/main/assets/source_catalog.json"))
    parser.add_argument("--desktop-output", type=Path, default=Path("OrigRead-Desktop/resources/source_catalog.json"))
    parser.add_argument("--summary-output", type=Path, default=Path("build/reports/source-catalog-audit-final-cleanup.json"))
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    if catalog.get("feedCount") != len(catalog.get("feeds") or []):
        raise SystemExit("catalog.feedCount does not match feeds length")
    cleaned, summary = clean_catalog(catalog, [load_report(path) for path in args.report])
    print(json.dumps({key: value for key, value in summary.items() if key not in {"updated", "removedEntries"}}, ensure_ascii=False, indent=2))
    if args.dry_run:
        print("Dry run: catalog assets were not modified.")
        return 0

    payload = json.dumps(cleaned, ensure_ascii=False, separators=(",", ":"))
    for path in (args.android_output, args.desktop_output):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload, encoding="utf-8")
    args.summary_output.parent.mkdir(parents=True, exist_ok=True)
    args.summary_output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Android: {args.android_output}")
    print(f"Desktop: {args.desktop_output}")
    print(f"Summary: {args.summary_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
