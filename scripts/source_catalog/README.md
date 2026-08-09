# 来源发现目录生成器

`generate_source_catalog.py` 把公开 OPML 元数据转换成 OrigRead 的
`app/src/main/assets/source_catalog.json`。

## 当前数据源

- `plenaryapp/awesome-rss-feeds`
  - 使用 `recommended/without_category/*.opml`
  - 每个 OPML 文件名就是上游原始分类
  - 上游许可证：CC0-1.0
- `ginobefun/BestBlogs`
  - 使用 `BestBlogs_RSS_Articles.opml`
  - 该 OPML 是扁平文章源列表，因此单个源只继承上游明确的 `article` 类型，目录中显示为 `Articles`
  - 另外加入 BestBlogs 官方 RSS 文档明确公开的 Programming / AI / Product / Business 四个分类频道

## 分类原则

1. 不请求任何 RSS/Atom 正文。
2. 不通过 AI、关键词或源名称猜测领域。
3. 优先原样保留上游分类，只对跨数据集明确同义的名称做少量归一化。
4. 同一 Feed 被多个数据集收录时去重，但保留全部 `origins` 和分类。

## 生成

```bash
python scripts/source_catalog/generate_source_catalog.py \
  --awesome-dir <awesome-rss-feeds>/recommended/without_category \
  --bestblogs-opml <BestBlogs>/BestBlogs_RSS_Articles.opml \
  --output app/src/main/assets/source_catalog.json
```

生成后运行 `FeedDiscoveryCatalogAssetTest`，确保目录 schema、数量、URL 与来源归属有效。
