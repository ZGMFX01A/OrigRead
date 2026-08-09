# 网站解析规则使用说明

网站解析规则用于处理**服务器已经返回完整 HTML 的文章列表页**。原读使用 Jsoup 读取 HTML，不会因为一条规则去执行页面 JavaScript。

如果网站本身提供 RSS、JSON API 或已经能被自动 DOM 识别，一般不需要手写规则。只有自动结果不理想、你希望长期固定解析方式时，才建议添加规则。

## 1. 先理解三个层级

一条规则的解析顺序是：

```text
整个列表页面
  ↓ articleSelectors
找到每一条“文章卡片”
  ↓ titleSelector / linkSelector / dateRules / imageSelector
从每张卡片中取标题、链接、时间、图片
  ↓ 用户打开文章详情页
contentSelectors
  ↓
提取正文；失败时再回退通用正文提取
```

最容易写错的一点是：`titleSelector`、`linkSelector`、`dateRules.selector`、`imageSelector` **都是相对于单个 articleSelector 节点执行的**，不是再次从整个页面搜索。

## 2. 最小可用示例

假设网页结构是：

```html
<div class="news-list">
  <article class="news-item">
    <a class="title" href="/news/123">新品发布</a>
    <time>2026-08-08 12:30</time>
    <img data-src="/images/123.jpg">
  </article>
</div>
```

对应规则：

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "id": "example-news",
      "name": "Example 新闻",
      "version": 1,
      "enabled": true,
      "hosts": ["example.com"],
      "articleSelectors": [".news-list .news-item"],
      "titleSelector": "a.title",
      "linkSelector": "a.title",
      "linkAttribute": "href",
      "dateRules": [
        {
          "selector": "time",
          "pattern": "yyyy-MM-dd HH:mm"
        }
      ],
      "imageSelector": "img",
      "imageAttributes": ["data-src", "src"],
      "contentSelectors": ["article .content", "main article"],
      "maxItems": 50
    }
  ]
}
```

## 3. 文件最外层

导入文件必须是一个规则包：

```json
{
  "schemaVersion": 1,
  "rules": []
}
```

- `schemaVersion`：目前必须为 `1`。
- `rules`：可以同时放多条规则。
- 同一个 `id` 再次导入时，新规则会覆盖旧规则。
- 未知字段会被忽略，但不要依赖这一点保存自定义数据。

## 4. 基础字段

### `id`

规则的唯一 ID，例如：

```json
"id": "ithome-home"
```

建议只使用小写英文、数字和短横线，并长期保持不变。

### `name`

用户在“网站解析规则”页面看到的名称。

### `version`

规则自己的版本号，默认 `1`。你修改规则内容后可以递增，方便人工维护。

### `enabled`

是否启用。设为 `false` 后规则仍然保存，但不会参与匹配。

### `hosts`

这条规则适用于哪些域名：

```json
"hosts": ["example.com"]
```

只能写**纯域名**，不能写：

```text
https://example.com
example.com/news
https://example.com/news
```

写 `example.com` 时也会匹配它的子域名，例如 `www.example.com`、`news.example.com`。

## 5. 找到文章卡片：`articleSelectors`

这是最重要的字段。

```json
"articleSelectors": [
  ".news-list .news-item",
  "main article"
]
```

它使用 **Jsoup CSS Selector**。

原读会按顺序尝试，使用**第一个能匹配到节点的 selector**，不是把多个 selector 的结果合并。

常见写法：

```text
.news-list li
ul.article-list > li
main article
#content .post-card
div[data-type=article]
```

建议让一条匹配结果刚好对应“一篇文章卡片”。不要直接选择整个列表容器，也不要选择页面所有 `a`。

## 6. 标题：`titleSelector`

在每个文章卡片内部寻找标题：

```json
"titleSelector": "h2 a"
```

原读读取匹配元素的可见文本 `.text()`。

例如：

```html
<article class="item">
  <h2><a href="/123">真正的文章标题</a></h2>
  <p>摘要……</p>
</article>
```

推荐：

```json
"articleSelectors": ["article.item"],
"titleSelector": "h2 a"
```

不要把 `titleSelector` 写成整个 `article.item`，否则摘要、作者等文字也可能被拼进标题。

## 7. 链接：`linkSelector` 与 `linkAttribute`

默认情况下：

```json
"linkSelector": "h2 a",
"linkAttribute": "href"
```

原读会读取元素的绝对 URL，因此网页里的相对地址：

```text
/news/123
```

会自动补成：

```text
https://example.com/news/123
```

如果链接不在标题元素上，可以单独指定：

```html
<article class="item" data-url="/news/123">
  <h2>标题</h2>
</article>
```

可以写：

```json
"titleSelector": "h2",
"linkSelector": ".item-link",
"linkAttribute": "href"
```

当前规则模型仍要求 `linkSelector` 能选中一个真实元素；不要把 CSS 属性表达式误当成 selector。

## 8. 发布时间：`dateRules`

可以提供多个时间提取方案：

```json
"dateRules": [
  {
    "selector": "time",
    "pattern": "yyyy-MM-dd HH:mm:ss"
  },
  {
    "selector": ".date",
    "pattern": "yyyy-MM-dd"
  }
]
```

原读按顺序尝试，读取 selector 的文本，再使用 Java `SimpleDateFormat` 解析。

常见 pattern：

```text
yyyy-MM-dd HH:mm:ss
yyyy-MM-dd HH:mm
yyyy/MM/dd HH:mm
yyyy-MM-dd
MM-dd
HH:mm
```

`HH:mm` 会自动补当前抓取日期；`MM-dd` 会自动补当前年份。所有规则都失败时使用本次抓取时间。

如果你导入的是自动 DOM 生成规则，可能看到：

```json
"automaticDateExtraction": true
```

这是原读内部自动识别字段，手写规则通常不要设置它。

## 9. 列表图片：`imageSelector` 与 `imageAttributes`

例如：

```html
<img data-original="/cover.jpg" src="placeholder.jpg">
```

规则：

```json
"imageSelector": "img",
"imageAttributes": ["data-original", "data-src", "src"]
```

原读会按 `imageAttributes` 顺序读取**第一个非空 URL**，相对地址会自动补全。

如果网站没有列表图，可以直接省略 `imageSelector`。

## 10. 正文：`contentSelectors`

这个字段不是在列表页执行，而是在用户打开文章详情页、原读抓全文时执行。

```json
"contentSelectors": [
  "article .article-content",
  "main .post-content",
  "article"
]
```

原读按顺序选择第一个**存在且有文本**的正文节点。明确正文规则的候选优先于通用 Readability。

如果全部失败或 `contentSelectors` 为空，仍会继续尝试结构化数据、Readability 和必要的动态页面兜底。

所以不确定时宁可省略，也不要写一个过大的 `body`。

## 11. 过滤链接：`includeUrlRegex`

当列表里同时有文章、栏目、专题、作者链接时，可以用正则只保留文章 URL：

```json
"includeUrlRegex": "^https?://(?:www\\.)?example\\.com/news/\\d+(?:\\?.*)?$"
```

注意 JSON 字符串里的反斜杠需要写成 `\\`。

它对**补全后的绝对文章 URL**执行完整 `Regex.matches()`，因此通常需要写 `^...$`。

## 12. 过滤标题：`excludeTitleRegexes`

```json
"excludeTitleRegexes": [
  ".*广告.*",
  "(?i).*sponsored.*"
]
```

命中任意一条的文章会被丢弃。

## 13. `maxItems`

每次最多保留多少篇：

```json
"maxItems": 50
```

合法范围为 `1` 到 `200`。

## 14. `cleanupMode` 与 `urlIdRegex`

普通用户建议保持：

```json
"cleanupMode": "NONE"
```

`URL_ID_RANGE` 是针对“文章 URL 中存在单调数字 ID”的特殊清理模式，例如：

```json
"cleanupMode": "URL_ID_RANGE",
"urlIdRegex": "/(\\d+)\\.htm"
```

同步后，原读可以依据本次抓到的最旧 ID 清理由规则误收、且不在当前有效区间的旧项目。收藏文章不会被自动清理。

如果你不能确定站点 ID 是否稳定递增，**不要开启这个模式**。

## 15. `automatic*` 字段不要手写

以下字段主要由原读自动 DOM 检测器生成和维护：

```text
automaticUrlPattern
automaticDateExtraction
automaticRegionScore
```

人工规则通常省略即可。手动填写错误值反而可能过滤掉正确文章。

## 16. 推荐的制作流程

1. 用桌面浏览器打开目标列表页。
2. 按 F12 打开开发者工具。
3. 找到一条正常文章卡片，确定共同父节点。
4. 先只写 `articleSelectors + titleSelector + linkSelector`。
5. 在原读“测试网站规则”里确认能解析出正确文章。
6. 再补时间、图片、URL 正则。
7. 最后打开一篇详情页，再补 `contentSelectors`。
8. 测试正常后再长期启用。

> 不要一开始就把所有字段写满。规则越简单，网站轻微改版时越不容易失效。

## 17. 常见错误

### 一篇都解析不出来

先检查 `articleSelectors`。它必须在**服务器返回的静态 HTML**里真实存在。

如果浏览器开发者工具能看到，但“查看网页源代码”看不到，很可能是 JavaScript 动态渲染。此时手写静态规则可能无效，应让原读走动态网页兜底。

### 标题变成一大段

`titleSelector` 选得太大。把它缩到真正的 `h1/h2/h3/a.title`。

### 链接为空

检查 `linkSelector` 是否选中了带 URL 属性的元素，并确认 `linkAttribute` 是否真的是 `href`。

### 混入栏目、作者、标签

优先缩小 `articleSelectors`；仍有污染时再加 `includeUrlRegex`。

### 时间全变成同步时间

表示 `dateRules` 没有成功解析。先确认 selector 取到的原始文本，再核对 `SimpleDateFormat` pattern。

### 正文只有导航或整页

`contentSelectors` 太宽。应选择真正正文容器，而不是 `body`、`main` 的大范围外层。

## 18. 完整模板

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "id": "example-news-site",
      "name": "Example News Site",
      "version": 1,
      "enabled": true,
      "hosts": ["news.example.com"],
      "articleSelectors": [
        ".news-list .news-item",
        "article.news-item"
      ],
      "titleSelector": "a.title",
      "linkSelector": "a.title",
      "linkAttribute": "href",
      "dateRules": [
        {
          "selector": ".time",
          "pattern": "yyyy-MM-dd HH:mm"
        }
      ],
      "imageSelector": "img",
      "imageAttributes": ["data-original", "data-src", "src"],
      "contentSelectors": [
        "article .article-content",
        "main article"
      ],
      "includeUrlRegex": "^https?://news\\.example\\.com/.*$",
      "excludeTitleRegexes": [".*广告.*"],
      "maxItems": 50,
      "cleanupMode": "NONE"
    }
  ]
}
```
