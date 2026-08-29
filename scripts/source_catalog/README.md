# 来源发现目录生成器

`generate_source_catalog.py` 把公开 OPML 元数据转换成 OrigRead 的
`app/src/main/assets/source_catalog.json`。

## 当前数据源

- `plenaryapp/awesome-rss-feeds`
  - 使用 `recommended/without_category/*.opml`
  - 每个 OPML 文件名保留为上游原始分类
  - 上游许可证：CC0-1.0
- `ginobefun/BestBlogs`
  - 使用 `BestBlogs_RSS_Articles.opml`
  - 该 OPML 是扁平文章源列表，因此单个源只继承上游明确的 `article` 类型
  - 另外加入 BestBlogs 官方 RSS 文档明确公开的 Programming / AI / Product / Business 四个分类频道
- `xiangyugongzuoliu/awesome-rss-feeds-list`
  - 使用 `feeds/*.opml` 的全部分类文件，共 2,000 条上游记录
  - `cn-*` / `en-*` 文件名原样保存在 `origins.category`
  - 上游许可证：CC0-1.0
- `JackyST0/awesome-rsshub-routes`
  - 使用根目录 `feeds.opml` 的全部条目
  - OPML 顶层分组原样保存在 `origins.category`
  - 上游许可证：CC0-1.0

## 分类原则

1. 不请求任何 RSS/Atom 正文。
2. 不通过 AI、关键词或源名称猜测领域。
3. 上游原分类完整写入 `origins`，不依靠名称或正文猜分类。
4. 发现页展示分类统一归一到少量稳定主题，避免几十个细分类挤满界面。
5. 同一 Feed 被多个数据集收录时按规范化 Feed URL 去重，同时保留全部上游归属和原分类。

## 生成

```bash
python scripts/source_catalog/generate_source_catalog.py \
  --awesome-dir <awesome-rss-feeds>/recommended/without_category \
  --bestblogs-opml <BestBlogs>/BestBlogs_RSS_Articles.opml \
  --awesome-list-dir <awesome-rss-feeds-list>/feeds \
  --rsshub-routes-opml <awesome-rsshub-routes>/feeds.opml \
  --output app/src/main/assets/source_catalog.json
```

生成后运行 `FeedDiscoveryCatalogAssetTest`，确保目录 schema、数量、URL 与来源归属有效。
