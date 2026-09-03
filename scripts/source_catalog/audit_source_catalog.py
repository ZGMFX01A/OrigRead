#!/usr/bin/env python3
"""Audit OrigRead's bundled RSS/Atom source catalog without mutating it.

The audit is intentionally conservative: a feed is never removed automatically.
It probes the configured feed URL, optionally tries to rediscover a replacement
from the configured site URL / HTML alternate links / common feed paths, then
writes JSON + CSV reports for manual review.
"""

from __future__ import annotations

import argparse
import csv
import json
import socket
import ssl
import sys
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, build_opener


USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
)
ACCEPT = "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.9, */*;q=0.8"
COMMON_FEED_PATHS = ("/feed", "/feed/", "/rss", "/rss.xml", "/atom.xml", "/feed.xml", "/index.xml")
HARD_DEAD_STATUSES = {404, 410}
BLOCKED_STATUSES = {401, 403, 429}
TRANSIENT_STATUSES = {408, 425, 500, 502, 503, 504}
MAX_BODY_BYTES = 4 * 1024 * 1024


@dataclass
class ProbeResult:
    url: str
    ok: bool
    status: int | None = None
    final_url: str | None = None
    content_type: str | None = None
    body: bytes = b""
    error_kind: str | None = None
    error: str | None = None
    elapsed_ms: int = 0


@dataclass
class ParsedFeed:
    title: str | None
    item_count: int
    latest_article_at: str | None


@dataclass
class AuditRecord:
    id: str
    name: str
    feedUrl: str
    siteUrl: str | None
    categories: list[str]
    origins: list[dict]
    classification: str
    healthScore: int
    suggestion: str
    reason: str
    httpStatus: int | None = None
    finalUrl: str | None = None
    resolvedFeedUrl: str | None = None
    contentType: str | None = None
    parseOk: bool = False
    itemCount: int = 0
    latestArticleAt: str | None = None
    ageDays: int | None = None
    responseMs: int | None = None
    errorKind: str | None = None
    error: str | None = None
    repairMethod: str | None = None
    attemptedUrls: list[str] = field(default_factory=list)


class AlternateFeedParser(HTMLParser):
    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.urls: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.casefold() != "link":
            return
        values = {key.casefold(): (value or "") for key, value in attrs}
        rel = values.get("rel", "").casefold().split()
        if "alternate" not in rel:
            return
        content_type = values.get("type", "").casefold()
        if not any(marker in content_type for marker in ("rss", "atom", "rdf", "xml")):
            return
        href = values.get("href", "").strip()
        if not href:
            return
        self.urls.append(urljoin(self.base_url, href))


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].casefold()


def parse_datetime(value: str | None) -> datetime | None:
    text = (value or "").strip()
    if not text:
        return None
    try:
        parsed = parsedate_to_datetime(text)
        if parsed is not None:
            return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except (TypeError, ValueError, OverflowError):
        pass
    normalized = text.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def decode_body(body: bytes, content_type: str | None) -> str:
    charset = None
    if content_type and "charset=" in content_type.casefold():
        charset = content_type.split("charset=", 1)[1].split(";", 1)[0].strip(" \"'")
    for encoding in [charset, "utf-8-sig", "utf-8", "gb18030", "latin-1"]:
        if not encoding:
            continue
        try:
            return body.decode(encoding)
        except (LookupError, UnicodeDecodeError):
            continue
    return body.decode("utf-8", errors="replace")


def parse_feed(body: bytes, content_type: str | None = None) -> ParsedFeed | None:
    if not body:
        return None
    text = decode_body(body, content_type).lstrip()
    if not text.startswith("<"):
        return None
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return None

    root_name = local_name(root.tag)
    if root_name not in {"rss", "rdf", "feed"}:
        return None

    title = None
    for node in root.iter():
        if local_name(node.tag) == "title" and (node.text or "").strip():
            title = (node.text or "").strip()
            break

    entries = [node for node in root.iter() if local_name(node.tag) in {"item", "entry"}]
    dates: list[datetime] = []
    for entry in entries:
        for node in entry.iter():
            if local_name(node.tag) not in {"pubdate", "published", "updated", "date"}:
                continue
            parsed = parse_datetime(node.text)
            if parsed:
                dates.append(parsed.astimezone(timezone.utc))
                break

    latest = max(dates) if dates else None
    if not title and not entries:
        return None
    return ParsedFeed(
        title=title,
        item_count=len(entries),
        latest_article_at=latest.isoformat().replace("+00:00", "Z") if latest else None,
    )


def extract_alternate_urls(body: bytes, base_url: str, content_type: str | None = None) -> list[str]:
    parser = AlternateFeedParser(base_url)
    try:
        parser.feed(decode_body(body, content_type))
    except Exception:
        return []
    return distinct_http_urls(parser.urls)


def common_feed_candidates(url: str | None) -> list[str]:
    if not url:
        return []
    try:
        parts = urlsplit(url)
    except ValueError:
        return []
    if parts.scheme not in {"http", "https"} or not parts.netloc:
        return []
    origin = f"{parts.scheme}://{parts.netloc}"
    return [origin + path for path in COMMON_FEED_PATHS]


def distinct_http_urls(urls: Iterable[str | None]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for raw in urls:
        value = (raw or "").strip()
        if not value:
            continue
        try:
            parts = urlsplit(value)
        except ValueError:
            continue
        if parts.scheme not in {"http", "https"} or not parts.netloc:
            continue
        key = value.rstrip("/").casefold()
        if key in seen:
            continue
        seen.add(key)
        result.append(value)
    return result


def normalized_url_key(url: str | None) -> str:
    if not url:
        return ""
    try:
        parts = urlsplit(url)
    except ValueError:
        return url.rstrip("/").casefold()
    path = parts.path.rstrip("/") or "/"
    return f"{parts.scheme.casefold()}://{parts.netloc.casefold()}{path}?{parts.query}".rstrip("?")


def normalized_host(url: str | None) -> str:
    if not url:
        return ""
    try:
        host = (urlsplit(url).hostname or "").casefold()
    except ValueError:
        return ""
    return host.removeprefix("www.")


def classify_network_error(error: BaseException) -> tuple[str, str]:
    if isinstance(error, socket.timeout) or isinstance(error, TimeoutError):
        return "TIMEOUT", str(error)
    if isinstance(error, ssl.SSLError):
        return "TLS", str(error)
    if isinstance(error, URLError):
        reason = error.reason
        if isinstance(reason, socket.timeout):
            return "TIMEOUT", str(reason)
        if isinstance(reason, ssl.SSLError):
            return "TLS", str(reason)
        if isinstance(reason, OSError):
            return "NETWORK", str(reason)
        return "NETWORK", str(reason)
    if isinstance(error, OSError):
        return "NETWORK", str(error)
    return "UNKNOWN", str(error)


def fetch_url(url: str, timeout: float, retries: int) -> ProbeResult:
    started = time.perf_counter()
    opener = build_opener()
    last_error_kind = None
    last_error = None
    last_status = None
    attempts = max(1, retries + 1)

    for attempt in range(attempts):
        try:
            request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": ACCEPT})
            with opener.open(request, timeout=timeout) as response:
                body = response.read(MAX_BODY_BYTES + 1)
                if len(body) > MAX_BODY_BYTES:
                    body = body[:MAX_BODY_BYTES]
                return ProbeResult(
                    url=url,
                    ok=True,
                    status=getattr(response, "status", None) or response.getcode(),
                    final_url=response.geturl(),
                    content_type=response.headers.get("Content-Type"),
                    body=body,
                    elapsed_ms=round((time.perf_counter() - started) * 1000),
                )
        except HTTPError as error:
            last_status = error.code
            last_error_kind = "HTTP"
            last_error = f"HTTP {error.code}"
            if error.code not in TRANSIENT_STATUSES or attempt + 1 >= attempts:
                break
        except BaseException as error:  # noqa: BLE001 - audit must classify arbitrary network failures.
            last_error_kind, last_error = classify_network_error(error)
            if attempt + 1 >= attempts:
                break
        time.sleep(min(0.4 * (attempt + 1), 1.2))

    return ProbeResult(
        url=url,
        ok=False,
        status=last_status,
        error_kind=last_error_kind,
        error=last_error,
        elapsed_ms=round((time.perf_counter() - started) * 1000),
    )


def age_days(latest_article_at: str | None, now: datetime) -> int | None:
    parsed = parse_datetime(latest_article_at)
    if not parsed:
        return None
    delta = now - parsed.astimezone(timezone.utc)
    return max(0, delta.days)


def health_score(
    *,
    probe: ProbeResult,
    parsed: ParsedFeed | None,
    age: int | None,
    stale_days: int,
    resolved_url: str | None,
    original_url: str,
) -> int:
    score = 0
    if probe.ok:
        score += 30
    if parsed:
        score += 30
    if parsed and age is not None and age <= stale_days:
        score += 20
    elif parsed and age is None and parsed.item_count > 0:
        score += 10
    if parsed and parsed.item_count >= 10:
        score += 10
    elif parsed and parsed.item_count > 0:
        score += 5
    if resolved_url and normalized_url_key(resolved_url) == normalized_url_key(original_url):
        score += 5
    if probe.elapsed_ms <= 3000 and probe.ok:
        score += 5
    return min(100, score)


def classify_success(parsed: ParsedFeed, age: int | None, stale_days: int) -> tuple[str, str, str]:
    if parsed.item_count == 0:
        return "LOW_QUALITY", "Feed 可解析，但当前没有任何文章条目", "保留待人工审核"
    if age is not None and age > stale_days:
        return "STALE", f"最近一篇文章距今约 {age} 天", "降级展示或人工审核"
    if age is None:
        return "HEALTHY", "Feed 可解析且包含文章，但条目日期不可用", "保留"
    return "HEALTHY", f"Feed 正常，最近更新距今约 {age} 天", "保留"


def classify_failure(probe: ProbeResult) -> tuple[str, str, str]:
    if probe.status in BLOCKED_STATUSES:
        return "BLOCKED", f"请求被拒绝：HTTP {probe.status}", "不要自动删除；人工复核或稍后重试"
    if probe.status in HARD_DEAD_STATUSES:
        return "DEAD", f"Feed 地址返回 HTTP {probe.status}，且未发现可用替代 Feed", "人工确认后删除或替换"
    if probe.status in TRANSIENT_STATUSES:
        return "TEMP_ERROR", f"服务端暂时异常：HTTP {probe.status}", "稍后重试"
    if probe.ok:
        return "BROKEN", "HTTP 请求成功，但内容无法解析为 RSS/Atom", "检查站点是否迁移或改版"
    if probe.error_kind in {"TIMEOUT", "TLS", "NETWORK"}:
        return "TEMP_ERROR", f"网络访问失败：{probe.error or probe.error_kind}", "稍后重试；不要自动删除"
    return "BROKEN", probe.error or "未知错误", "人工审核"


def should_attempt_repair(probe: ProbeResult) -> bool:
    """Only spend extra requests on failures that are plausibly a moved feed.

    403/429, timeouts, TLS failures and 5xx are deliberately *not* rediscovered in
    the same pass. Treating those as migration candidates both slows a 2k+ catalog
    audit dramatically and risks turning temporary anti-bot/network failures into
    false replacement suggestions.
    """
    if probe.ok:
        return True
    return probe.status in HARD_DEAD_STATUSES


def attempt_repair(
    *,
    entry: dict,
    direct_probe: ProbeResult,
    timeout: float,
    retries: int,
) -> tuple[str | None, ParsedFeed | None, ProbeResult | None, str | None, list[str]]:
    candidates: list[tuple[str, str]] = []
    attempted: list[str] = []

    if direct_probe.ok and direct_probe.body:
        candidates.extend(
            (url, "direct-html-alternate")
            for url in extract_alternate_urls(
                direct_probe.body,
                direct_probe.final_url or entry["feedUrl"],
                direct_probe.content_type,
            )
        )

    site_url = entry.get("siteUrl")
    if site_url:
        site_probe = fetch_url(site_url, timeout, retries)
        attempted.append(site_url)
        if site_probe.ok:
            candidates.extend(
                (url, "site-html-alternate")
                for url in extract_alternate_urls(site_probe.body, site_probe.final_url or site_url, site_probe.content_type)
            )
            candidates.extend((url, "site-common-path") for url in common_feed_candidates(site_probe.final_url or site_url))

    # Do not blindly try /feed or /rss.xml on a third-party feed host such as
    # FeedBurner/Megaphone when the catalog also knows the actual website host.
    # Those services can expose a perfectly valid *unrelated* generic feed at a
    # common path, which would create a dangerous false "MOVED" recommendation.
    feed_url = entry.get("feedUrl")
    if not site_url or normalized_host(feed_url) == normalized_host(site_url):
        candidates.extend((url, "feed-origin-common-path") for url in common_feed_candidates(feed_url))
    if site_url and not any(method == "site-common-path" for _, method in candidates):
        candidates.extend((url, "site-common-path") for url in common_feed_candidates(site_url))
    original_key = normalized_url_key(entry.get("feedUrl"))

    seen: set[str] = set()
    for candidate, method in candidates:
        candidate_key = normalized_url_key(candidate)
        if not candidate_key or candidate_key in seen:
            continue
        seen.add(candidate_key)
        if normalized_url_key(candidate) == original_key:
            continue
        attempted.append(candidate)
        probe = fetch_url(candidate, timeout, retries)
        if not probe.ok:
            continue
        parsed = parse_feed(probe.body, probe.content_type)
        if parsed:
            return probe.final_url or candidate, parsed, probe, method, attempted
    return None, None, None, None, attempted


def audit_entry(entry: dict, *, timeout: float, retries: int, stale_days: int, now: datetime) -> AuditRecord:
    feed_url = entry["feedUrl"]
    direct = fetch_url(feed_url, timeout, retries)
    attempted = [feed_url]
    parsed = parse_feed(direct.body, direct.content_type) if direct.ok else None
    resolved_url = direct.final_url if parsed else None
    repair_method = None
    effective_probe = direct

    if parsed is None and should_attempt_repair(direct):
        repaired_url, repaired_feed, repaired_probe, method, repair_attempts = attempt_repair(
            entry=entry,
            direct_probe=direct,
            timeout=timeout,
            retries=retries,
        )
        attempted.extend(repair_attempts)
        if repaired_url and repaired_feed and repaired_probe:
            parsed = repaired_feed
            resolved_url = repaired_url
            effective_probe = repaired_probe
            repair_method = method

    latest = parsed.latest_article_at if parsed else None
    age = age_days(latest, now)

    if parsed and repair_method:
        classification = "MOVED"
        reason = f"原 Feed 不可用或已迁移，发现可解析的新地址：{resolved_url}"
        suggestion = "人工确认后更新 feedUrl"
    elif parsed:
        classification, reason, suggestion = classify_success(parsed, age, stale_days)
    else:
        classification, reason, suggestion = classify_failure(direct)

    score = health_score(
        probe=effective_probe,
        parsed=parsed,
        age=age,
        stale_days=stale_days,
        resolved_url=resolved_url,
        original_url=feed_url,
    )

    return AuditRecord(
        id=entry.get("id", ""),
        name=entry.get("name", feed_url),
        feedUrl=feed_url,
        siteUrl=entry.get("siteUrl"),
        categories=list(entry.get("categories") or []),
        origins=list(entry.get("origins") or []),
        classification=classification,
        healthScore=score,
        suggestion=suggestion,
        reason=reason,
        httpStatus=direct.status,
        finalUrl=direct.final_url,
        resolvedFeedUrl=resolved_url,
        contentType=direct.content_type,
        parseOk=parsed is not None,
        itemCount=parsed.item_count if parsed else 0,
        latestArticleAt=latest,
        ageDays=age,
        responseMs=direct.elapsed_ms,
        errorKind=direct.error_kind,
        error=direct.error,
        repairMethod=repair_method,
        attemptedUrls=distinct_http_urls(attempted),
    )


def load_catalog(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    feeds = data.get("feeds")
    if not isinstance(feeds, list):
        raise ValueError("catalog.feeds is missing or invalid")
    if data.get("feedCount") != len(feeds):
        raise ValueError("catalog.feedCount does not match feeds length")
    return data


def load_partial(path: Path) -> dict[str, AuditRecord]:
    if not path.exists():
        return {}
    records: dict[str, AuditRecord] = {}
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            raw = json.loads(line)
            record = AuditRecord(**raw)
            records[record.id] = record
    return records


def append_partial(path: Path, record: AuditRecord) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(asdict(record), ensure_ascii=False, separators=(",", ":")) + "\n")


def build_summary(records: list[AuditRecord]) -> dict:
    by_classification: dict[str, int] = {}
    by_origin: dict[str, dict[str, int]] = {}
    for record in records:
        by_classification[record.classification] = by_classification.get(record.classification, 0) + 1
        origin_ids = {origin.get("sourceId", "unknown") for origin in record.origins} or {"unknown"}
        for source_id in origin_ids:
            bucket = by_origin.setdefault(source_id, {})
            bucket[record.classification] = bucket.get(record.classification, 0) + 1
    return {
        "total": len(records),
        "byClassification": dict(sorted(by_classification.items())),
        "byOrigin": {key: dict(sorted(value.items())) for key, value in sorted(by_origin.items())},
    }


def write_json_report(path: Path, *, catalog_path: Path, records: list[AuditRecord], started_at: str, args: argparse.Namespace) -> None:
    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "catalogPath": str(catalog_path),
        "startedAt": started_at,
        "settings": {
            "timeoutSeconds": args.timeout,
            "retries": args.retries,
            "concurrency": args.concurrency,
            "staleDays": args.stale_days,
        },
        "summary": build_summary(records),
        "feeds": [asdict(record) for record in records],
    }
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


def write_csv_report(path: Path, records: list[AuditRecord]) -> None:
    fields = [
        "id", "name", "feedUrl", "siteUrl", "classification", "healthScore", "suggestion", "reason",
        "httpStatus", "finalUrl", "resolvedFeedUrl", "contentType", "parseOk", "itemCount",
        "latestArticleAt", "ageDays", "responseMs", "errorKind", "error", "repairMethod",
        "categories", "origins", "attemptedUrls",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for record in records:
            row = asdict(record)
            row["categories"] = " | ".join(record.categories)
            row["origins"] = " | ".join(f"{item.get('sourceId', '')}:{item.get('category', '')}" for item in record.origins)
            row["attemptedUrls"] = " | ".join(record.attemptedUrls)
            writer.writerow({field: row.get(field) for field in fields})


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit OrigRead bundled RSS/Atom catalog without deleting sources")
    parser.add_argument("--catalog", type=Path, default=Path("app/src/main/assets/source_catalog.json"))
    parser.add_argument("--output-dir", type=Path, default=Path("build/reports/source-catalog-audit"))
    parser.add_argument("--concurrency", type=int, default=24)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--retries", type=int, default=1)
    parser.add_argument("--stale-days", type=int, default=365)
    parser.add_argument("--limit", type=int, default=0, help="Only audit the first N pending feeds; 0 means all")
    parser.add_argument("--resume", action="store_true", help="Reuse completed rows from the partial JSONL file")
    parser.add_argument(
        "--recheck-report",
        type=Path,
        help="Only audit IDs whose previous report classification matches --recheck-classifications",
    )
    parser.add_argument(
        "--recheck-classifications",
        default="TEMP_ERROR,BROKEN,DEAD",
        help="Comma-separated classifications selected from --recheck-report",
    )
    parser.add_argument("--progress-every", type=int, default=25)
    return parser.parse_args()


def recheck_ids(report_path: Path, classifications: str) -> set[str]:
    report = json.loads(report_path.read_text(encoding="utf-8"))
    wanted = {item.strip().upper() for item in classifications.split(",") if item.strip()}
    feeds = report.get("feeds")
    if not isinstance(feeds, list):
        raise ValueError("recheck report feeds is missing or invalid")
    return {
        str(item.get("id"))
        for item in feeds
        if item.get("id") and str(item.get("classification", "")).upper() in wanted
    }


def main() -> int:
    args = parse_args()
    if args.concurrency < 1:
        raise SystemExit("--concurrency must be >= 1")
    if args.timeout <= 0:
        raise SystemExit("--timeout must be > 0")
    if args.retries < 0:
        raise SystemExit("--retries must be >= 0")

    catalog = load_catalog(args.catalog)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    partial_path = args.output_dir / "source_catalog_audit.partial.jsonl"
    json_path = args.output_dir / "source_catalog_audit.json"
    csv_path = args.output_dir / "source_catalog_audit.csv"

    completed = load_partial(partial_path) if args.resume else {}
    if not args.resume and partial_path.exists():
        partial_path.unlink()

    allowed_ids = None
    if args.recheck_report:
        allowed_ids = recheck_ids(args.recheck_report, args.recheck_classifications)
        print(
            f"Recheck selection: {len(allowed_ids)} feed(s) from {args.recheck_report} "
            f"[{args.recheck_classifications}]",
            flush=True,
        )

    feeds = [
        entry
        for entry in catalog["feeds"]
        if entry.get("id") not in completed and (allowed_ids is None or entry.get("id") in allowed_ids)
    ]
    if args.limit > 0:
        feeds = feeds[: args.limit]

    started = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    now = datetime.now(timezone.utc)
    total_target = len(completed) + len(feeds)
    print(
        f"Auditing {len(feeds)} feed(s), {len(completed)} resumed, concurrency={args.concurrency}, "
        f"timeout={args.timeout}s, retries={args.retries}",
        flush=True,
    )

    if feeds:
        with ThreadPoolExecutor(max_workers=args.concurrency, thread_name_prefix="feed-audit") as executor:
            future_map = {
                executor.submit(
                    audit_entry,
                    entry,
                    timeout=args.timeout,
                    retries=args.retries,
                    stale_days=args.stale_days,
                    now=now,
                ): entry
                for entry in feeds
            }
            finished = 0
            for future in as_completed(future_map):
                entry = future_map[future]
                try:
                    record = future.result()
                except BaseException as error:  # noqa: BLE001
                    record = AuditRecord(
                        id=entry.get("id", ""),
                        name=entry.get("name", entry.get("feedUrl", "")),
                        feedUrl=entry.get("feedUrl", ""),
                        siteUrl=entry.get("siteUrl"),
                        categories=list(entry.get("categories") or []),
                        origins=list(entry.get("origins") or []),
                        classification="TEMP_ERROR",
                        healthScore=0,
                        suggestion="稍后重试；不要自动删除",
                        reason=f"审计器异常：{error}",
                        errorKind="AUDITOR",
                        error=str(error),
                    )
                completed[record.id] = record
                append_partial(partial_path, record)
                finished += 1
                if finished == 1 or finished % max(1, args.progress_every) == 0 or finished == len(feeds):
                    counts = build_summary(list(completed.values()))["byClassification"]
                    print(
                        f"[{len(completed)}/{total_target}] {record.classification:<11} {record.name[:60]} | {counts}",
                        flush=True,
                    )

    ordered_records = [completed[entry["id"]] for entry in catalog["feeds"] if entry.get("id") in completed]
    write_json_report(json_path, catalog_path=args.catalog, records=ordered_records, started_at=started, args=args)
    write_csv_report(csv_path, ordered_records)

    summary = build_summary(ordered_records)
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    print(f"JSON: {json_path}", flush=True)
    print(f"CSV : {csv_path}", flush=True)
    print("No catalog entries were modified or deleted.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
