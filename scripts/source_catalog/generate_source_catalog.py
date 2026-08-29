#!/usr/bin/env python3
"""生成 OrigRead 内置 RSS 来源目录。

只读取上游 OPML 的标题、RSS URL、站点 URL 和原始分类等元数据；不会请求 Feed 正文，
也不会基于文章内容进行二次分类。上游原分类完整保存在 origins，展示分类归一到少量稳定主题。
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


# 发现页展示分类故意保持精简。原始分类不会丢失，会继续写入 origins.sourceCategory。
# key 同时覆盖旧目录分类、xiangyugongzuoliu 分类文件名和 JackyST0 OPML 分组名。
CATEGORY_ALIASES = {
    # AI
    "ai": "AI",
    "cn-ai-research": "AI",
    "cn-ai-tools": "AI",
    "en-ai-research": "AI",
    "en-ai-tools": "AI",
    "ai 专题": "AI",

    # 编程与工程
    "programming": "Programming",
    "web development": "Programming",
    "cn-backend": "Programming",
    "en-backend": "Programming",
    "cn-web-frontend": "Programming",
    "en-web-frontend": "Programming",
    "前端 & 设计": "Programming",
    "编程语言官方博客": "Programming",
    "tech": "Tech & Engineering",
    "cn-tech-teams": "Tech & Engineering",
    "en-tech-teams": "Tech & Engineering",
    "技术社区": "Tech & Engineering",
    "大厂技术博客": "Tech & Engineering",

    # 工具 / DevOps / 移动
    "cn-dev-tools": "Developer Tools",
    "en-dev-tools": "Developer Tools",
    "开发工具版本追踪": "Developer Tools",
    "rss 工具更新": "Developer Tools",
    "cn-devops-data": "DevOps & Data",
    "en-devops-data": "DevOps & Data",
    "android": "Mobile",
    "android development": "Mobile",
    "apple": "Mobile",
    "ios development": "Mobile",
    "cn-mobile": "Mobile",
    "en-mobile": "Mobile",

    # 产品 / 安全
    "product": "Product & Design",
    "ui - ux": "Product & Design",
    "cn-product-design": "Product & Design",
    "en-product-design": "Product & Design",
    "cyber security": "Security",
    "安全资讯": "Security",

    # 商业 / 创业
    "business": "Business & Finance",
    "business & economy": "Business & Finance",
    "cryptocurrency": "Business & Finance",
    "personal finance": "Business & Finance",
    "cn-finance": "Business & Finance",
    "en-finance": "Business & Finance",
    "startups": "Startups",
    "cn-startups": "Startups",
    "en-startups": "Startups",

    # 新闻 / 周刊 / 播客
    "news": "News",
    "新闻资讯": "News",
    "科技媒体": "News",
    "cn-newsletters": "Newsletters",
    "en-newsletters": "Newsletters",
    "技术周刊": "Newsletters",
    "cn-podcasts": "Podcasts",
    "en-podcasts": "Podcasts",

    # 科学 / 生活文化 / 娱乐 / 体育 / 随笔
    "animal & wildlife": "Science & Nature",
    "environment": "Science & Nature",
    "nature": "Science & Nature",
    "science": "Science & Nature",
    "space": "Science & Nature",
    "学术论文": "Science & Nature",
    "architecture": "Culture & Life",
    "beauty": "Culture & Life",
    "books": "Culture & Life",
    "cars": "Culture & Life",
    "diy": "Culture & Life",
    "fashion": "Culture & Life",
    "food": "Culture & Life",
    "history": "Culture & Life",
    "interior design": "Culture & Life",
    "photography": "Culture & Life",
    "travel": "Culture & Life",
    "cn-lifestyle": "Culture & Life",
    "en-lifestyle": "Culture & Life",
    "funny": "Media & Entertainment",
    "gaming": "Media & Entertainment",
    "memes": "Media & Entertainment",
    "movies": "Media & Entertainment",
    "music": "Media & Entertainment",
    "television": "Media & Entertainment",
    "chess": "Sports",
    "cricket": "Sports",
    "football": "Sports",
    "sports": "Sports",
    "tennis": "Sports",
    "articles": "Essays & Blogs",
    "cn-essays": "Essays & Blogs",
    "en-essays": "Essays & Blogs",
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


def parse_grouped_opml(path: Path) -> list[dict]:
    """读取带父分组的 OPML，并把最近的非 Feed outline 作为原始分类。"""
    root = ET.parse(path).getroot()
    body = root.find("body")
    if body is None:
        return []
    result = []

    def walk(node: ET.Element, category: str | None) -> None:
        feed_url = (node.attrib.get("xmlUrl") or "").strip()
        if feed_url:
            result.append(
                {
                    "name": (node.attrib.get("title") or node.attrib.get("text") or feed_url).strip(),
                    "feedUrl": feed_url,
                    "siteUrl": (node.attrib.get("htmlUrl") or "").strip() or None,
                    "category": category or "Articles",
                }
            )
            return
        next_category = (node.attrib.get("title") or node.attrib.get("text") or "").strip() or category
        for child in node.findall("outline"):
            walk(child, next_category)

    for outline in body.findall("outline"):
        walk(outline, None)
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


def load_awesome_list(feeds: OrderedDict[str, FeedEntry], feeds_dir: Path) -> None:
    """加载 xiangyugongzuoliu/awesome-rss-feeds-list 的全部分类 OPML。"""
    for opml in sorted(feeds_dir.glob("*.opml"), key=lambda p: p.name.casefold()):
        source_category = opml.stem
        for item in parse_opml(opml):
            add_feed(
                feeds,
                name=item["name"],
                feed_url=item["feedUrl"],
                site_url=item["siteUrl"],
                category=source_category,
                source_id="awesome-rss-feeds-list",
                source_category=source_category,
            )


def load_rsshub_routes(feeds: OrderedDict[str, FeedEntry], opml: Path) -> None:
    """加载 JackyST0/awesome-rsshub-routes，并继承其 OPML 顶层分组。"""
    for item in parse_grouped_opml(opml):
        source_category = item["category"]
        add_feed(
            feeds,
            name=item["name"],
            feed_url=item["feedUrl"],
            site_url=item["siteUrl"],
            category=source_category,
            source_id="awesome-rsshub-routes",
            source_category=source_category,
        )


def build_catalog(
    awesome_dir: Path,
    bestblogs_opml: Path,
    awesome_list_dir: Path,
    rsshub_routes_opml: Path,
) -> dict:
    feeds: OrderedDict[str, FeedEntry] = OrderedDict()
    load_awesome(feeds, awesome_dir)
    load_bestblogs(feeds, bestblogs_opml)
    load_awesome_list(feeds, awesome_list_dir)
    load_rsshub_routes(feeds, rsshub_routes_opml)

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
            {
                "id": "awesome-rss-feeds-list",
                "name": "awesome-rss-feeds-list",
                "url": "https://github.com/xiangyugongzuoliu/awesome-rss-feeds-list",
                "license": "CC0-1.0",
            },
            {
                "id": "awesome-rsshub-routes",
                "name": "awesome-rsshub-routes",
                "url": "https://github.com/JackyST0/awesome-rsshub-routes",
                "license": "CC0-1.0",
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
    parser.add_argument("--awesome-list-dir", type=Path, required=True)
    parser.add_argument("--rsshub-routes-opml", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    catalog = build_catalog(
        args.awesome_dir,
        args.bestblogs_opml,
        args.awesome_list_dir,
        args.rsshub_routes_opml,
    )
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
