#!/usr/bin/env python3
"""生成 OrigRead 内置 RSS 来源目录。

只读取上游 OPML 的标题、RSS URL、站点 URL 和原始分类等元数据；不会请求 Feed 正文，
也不会基于文章内容进行二次分类。分类仅做极少量跨数据集同义归一化。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit


# 只做跨数据集明显同义的合并，不把相邻但不同的技术领域强行归成一类。
CATEGORY_ALIASES = {
    "programming": "Programming",
    "business": "Business & Economy",
    "ai": "AI",
    "product": "Product",
}


@dataclass
class FeedEntry:
    name: str
    feed_url: str
    site_url: str | None = None
    categories: list[str] = field(default_factory=list)
    origins: list[dict] = field(default_factory=list)


def canonical_category(value: str) -> str:
    value = value.strip()
    return CATEGORY_ALIASES.get(value.casefold(), value)


def feed_key(url: str) -> str:
    """生成用于去重的 URL key，不修改最终实际订阅地址。"""
    parts = urlsplit(url.strip())
    host = parts.netloc.casefold()
    path = parts.path.rstrip("/") or "/"
    return urlunsplit(("", host, path, parts.query, ""))


def stable_id(url: str) -> str:
    return hashlib.sha256(feed_key(url).encode("utf-8")).hexdigest()[:20]


def parse_opml(path: Path) -> list[dict]:
    root = ET.parse(path).getroot()
    result = []
    for node in root.findall(".//outline[@xmlUrl]"):
        feed_url = (node.attrib.get("xmlUrl") or "").strip()
        if not feed_url:
            continue
        result.append(
            {
                "name": (node.attrib.get("title") or node.attrib.get("text") or feed_url).strip(),
                "feedUrl": feed_url,
                "siteUrl": (node.attrib.get("htmlUrl") or "").strip() or None,
            }
        )
    return result


def add_feed(
    feeds: OrderedDict[str, FeedEntry],
    *,
    name: str,
    feed_url: str,
    site_url: str | None,
    category: str,
    source_id: str,
    source_category: str,
) -> None:
    key = feed_key(feed_url)
    if not key:
        return
    category = canonical_category(category)
    entry = feeds.get(key)
    if entry is None:
        entry = FeedEntry(name=name, feed_url=feed_url, site_url=site_url)
        feeds[key] = entry
    elif entry.feed_url.startswith("http://") and feed_url.startswith("https://"):
        # 同一 Feed 同时存在 HTTP/HTTPS 时优先保留 HTTPS，但不主动探测或改写 URL。
        entry.feed_url = feed_url
    if not entry.site_url and site_url:
        entry.site_url = site_url
    if category not in entry.categories:
        entry.categories.append(category)
    origin = {"sourceId": source_id, "category": source_category}
    if origin not in entry.origins:
        entry.origins.append(origin)


def load_awesome(feeds: OrderedDict[str, FeedEntry], recommended_dir: Path) -> None:
    for opml in sorted(recommended_dir.glob("*.opml"), key=lambda p: p.name.casefold()):
        source_category = opml.stem
        for item in parse_opml(opml):
            add_feed(
                feeds,
                name=item["name"],
                feed_url=item["feedUrl"],
                site_url=item["siteUrl"],
                category=source_category,
                source_id="awesome-rss-feeds",
                source_category=source_category,
            )


def load_bestblogs(feeds: OrderedDict[str, FeedEntry], articles_opml: Path) -> None:
    # BestBlogs 的公开 Articles OPML 本身是扁平列表，没有给每个单独源附领域分类。
    # 因此只继承它明确声明的资源类型 Article，不根据名称或正文猜测类别。
    for item in parse_opml(articles_opml):
        add_feed(
            feeds,
            name=item["name"],
            feed_url=item["feedUrl"],
            site_url=item["siteUrl"],
            category="Articles",
            source_id="bestblogs",
            source_category="article",
        )

    # BestBlogs 官方 RSS 文档明确提供四个领域过滤器；把它们作为可直接订阅的精选频道加入目录。
    category_feeds = [
        ("Programming", "programming", "BestBlogs · Programming"),
        ("AI", "ai", "BestBlogs · AI"),
        ("Product", "product", "BestBlogs · Product"),
        ("Business & Economy", "business", "BestBlogs · Business"),
    ]
    for category, slug, name in category_feeds:
        add_feed(
            feeds,
            name=name,
            feed_url=f"https://www.bestblogs.dev/zh/feeds/rss?category={slug}&type=article",
            site_url="https://www.bestblogs.dev/",
            category=category,
            source_id="bestblogs",
            source_category=slug,
        )


def build_catalog(awesome_dir: Path, bestblogs_opml: Path) -> dict:
    feeds: OrderedDict[str, FeedEntry] = OrderedDict()
    load_awesome(feeds, awesome_dir)
    load_bestblogs(feeds, bestblogs_opml)

    all_categories = sorted(
        {category for entry in feeds.values() for category in entry.categories},
        key=str.casefold,
    )
    output_feeds = []
    for entry in feeds.values():
        output_feeds.append(
            {
                "id": stable_id(entry.feed_url),
                "name": entry.name,
                "feedUrl": entry.feed_url,
                "siteUrl": entry.site_url,
                "categories": sorted(entry.categories, key=str.casefold),
                "origins": entry.origins,
            }
        )
    output_feeds.sort(key=lambda item: item["name"].casefold())

    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "feedCount": len(output_feeds),
        "categories": all_categories,
        "sources": [
            {
                "id": "awesome-rss-feeds",
                "name": "awesome-rss-feeds",
                "url": "https://github.com/plenaryapp/awesome-rss-feeds",
                "license": "CC0-1.0",
            },
            {
                "id": "bestblogs",
                "name": "BestBlogs",
                "url": "https://github.com/ginobefun/BestBlogs",
                "license": None,
            },
        ],
        "feeds": output_feeds,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--awesome-dir",
        type=Path,
        required=True,
        help="awesome-rss-feeds/recommended/without_category 目录",
    )
    parser.add_argument("--bestblogs-opml", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    catalog = build_catalog(args.awesome_dir, args.bestblogs_opml)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(
        f"Generated {catalog['feedCount']} feeds / {len(catalog['categories'])} categories -> {args.output}"
    )


if __name__ == "__main__":
    main()
