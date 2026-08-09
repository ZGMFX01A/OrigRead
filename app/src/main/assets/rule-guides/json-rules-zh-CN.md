# JSON / API 规则使用说明

JSON 规则适用于**文章列表本身就是 JSON 数据**的站点，包括公开 REST API、WordPress REST，以及静态 HTML 中的 Next.js / Nuxt 内嵌 JSON。

它不是“从 HTML 里用 CSS 找文章”的规则。两者区别：

```text
网站解析规则：HTML → CSS Selector → 文章
JSON 规则：JSON → JSONPath → 文章
```

如果页面背后存在稳定公开 API，JSON 规则通常比 CSS 规则更稳定。

## 1. 最小可用例子

接口：

```text
https://api.example.com/v1/posts
```

返回：

```json
{
  "data": {
    "items": [
      {
        "id": 123,
        "title": "新品发布",
        "url": "/news/123",
        "publishedAt": "2026-08-08T12:30:00+08:00",
        "author": { "name": "作者 A" },
        "summary": "文章摘要",
        "cover": "/images/123.jpg"
      }
    ]
  }
}
```

规则：

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "id": "example-json-api",
      "name": "Example JSON API",
      "version": 1,
      "enabled": true,
      "hosts": ["example.com", "api.example.com"],
      "sourceKind": "API",
      "endpoint": "https://api.example.com/v1/posts",
      "itemsPath": "$.data.items[*]",
      "titlePath": "$.title",
      "linkPath": "$.url",
      "datePath": "$.publishedAt",
      "authorPath": "$.author.name",
      "descriptionPath": "$.summary",
      "imagePath": "$.cover",
      "idPath": "$.id",
      "maxItems": 50
    }
  ]
}
```

## 2. 最外层规则文件

```json
{
  "schemaVersion": 1,
  "rules": []
}
```

- `schemaVersion` 目前必须为 `1`。
- `rules` 可包含多条规则。
- 再次导入相同 `id` 时，新内容覆盖旧规则。

## 3. `sourceKind`：先确定 JSON 在哪里

目前支持三种：

```text
API
NEXT_DATA
NUXT_DATA
```

### `API`

原读直接请求 `endpoint`，响应正文必须是 JSON。

```json
"sourceKind": "API",
"endpoint": "/api/posts"
```

`endpoint` 可以是绝对 URL，也可以是相对于用户输入网址的地址。

例如用户添加：

```text
https://example.com/news/
```

规则：

```json
"endpoint": "/api/posts"
```

最终请求会解析为：

```text
https://example.com/api/posts
```

### `NEXT_DATA`

用于 Next.js 页面。原读请求用户输入的 HTML 页面，然后读取：

```html
<script id="__NEXT_DATA__" type="application/json">...</script>
```

通常写：

```json
"sourceKind": "NEXT_DATA",
"endpoint": "."
```

此时不会另外请求 API，`itemsPath` 等路径针对 `__NEXT_DATA__` 内部 JSON。

### `NUXT_DATA`

用于 Nuxt 页面。原读支持：

```html
<script id="__NUXT_DATA__" type="application/json">...</script>
```

以及：

```html
<script type="application/json" data-nuxt-data>...</script>
```

通常同样使用：

```json
"sourceKind": "NUXT_DATA",
"endpoint": "."
```

> NEXT_DATA / NUXT_DATA 只读取服务器返回 HTML 中已经存在的 JSON，不执行页面 JavaScript。

## 4. `hosts`

```json
"hosts": ["example.com"]
```

只能填写纯域名，不包含协议和路径。

`example.com` 会同时匹配它的子域名。

如果用户输入的是 `www.example.com`，规则 `hosts: ["example.com"]` 可以匹配。

## 5. 原读支持的 JSONPath 很有限

为了可预测和轻量，原读没有引入完整 JSONPath 引擎。

支持：

```text
$.data.items
$[0]
$.items[0]
$.items[*]
$.items[*].title
$.author.name
```

所有路径必须以 `$` 开头。

当前**不支持**：

```text
$..title                  递归查找
$.items[?(@.type=='a')]   条件过滤
$.items[0:10]             切片
$.items[0,2]              联合下标
$['strange-key']          方括号字符串字段
复杂脚本表达式
```

如果 API 必须依赖这些复杂表达式，建议寻找更直接的 endpoint，而不是把复杂查询逻辑塞进规则。

## 6. `itemsPath`：先找到文章数组

这是最重要的 JSONPath。

例如：

```json
{
  "data": {
    "items": [
      { "title": "A" },
      { "title": "B" }
    ]
  }
}
```

应写：

```json
"itemsPath": "$.data.items[*]"
```

经过 `itemsPath` 后，每个 `{ "title": ... }` 会成为一篇候选文章。

后面的 `titlePath`、`linkPath` 等路径，都是**相对于这一篇 item 再从 `$` 开始**。

因此写：

```json
"titlePath": "$.title"
```

而不是：

```json
"titlePath": "$.data.items[*].title"
```

## 7. `titlePath` 与 `linkPath`

这两个字段必填。

```json
"titlePath": "$.title",
"linkPath": "$.url"
```

标题和链接任意一个为空，这个 item 会被跳过。

标题如果包含 WordPress `rendered` 一类 HTML：

```json
"title": { "rendered": "Hello &amp; <b>World</b>" }
```

使用：

```json
"titlePath": "$.title.rendered"
```

原读会自动转成可读纯文本：

```text
Hello & World
```

相对文章 URL 会以当前 API / 页面地址为基础自动补全。

## 8. 可选字段

### `datePath`

```json
"datePath": "$.publishedAt"
```

支持数字时间戳：

```text
1723089600       秒
1723089600000    毫秒
```

小于 `10000000000` 的整数按秒处理，其余按毫秒处理。

也支持字符串日期。默认依次尝试：

```text
用户配置的 dateFormat
yyyy-MM-dd'T'HH:mm:ssXXX
yyyy-MM-dd HH:mm:ss
yyyy-MM-dd
```

都失败时使用本次抓取时间。

### `dateFormat`

如果服务端日期不是默认格式：

```json
"datePath": "$.pubDate",
"dateFormat": "yyyy/MM/dd HH:mm"
```

格式使用 Java `SimpleDateFormat`。

### `authorPath`

```json
"authorPath": "$.author.name"
```

作者文本会自动去除 HTML 标签和实体编码。

### `descriptionPath`

```json
"descriptionPath": "$.summary"
```

这里可以保留摘要 HTML，阅读器后续会按现有正文链处理。

### `imagePath`

```json
"imagePath": "$.cover"
```

相对图片 URL 会自动补全。

### `idPath`

```json
"idPath": "$.id"
```

用于生成稳定文章 ID。为空时原读回退使用文章链接，因此通常建议配置稳定 ID，但不是必须。

## 9. `maxItems`

```json
"maxItems": 50
```

合法范围 `1` 到 `200`。

重复文章链接会自动去重，然后再应用数量上限。

## 10. WordPress 一般不用自己写

标准 WordPress REST API：

```text
/wp-json/wp/v2/posts?_embed=1&per_page=30
```

原读已经有自动探测逻辑，并兼容 WordPress 安装在子目录中的情况。

它使用的核心结构大致相当于：

```json
{
  "sourceKind": "API",
  "itemsPath": "$[*]",
  "titlePath": "$.title.rendered",
  "linkPath": "$.link",
  "datePath": "$.date_gmt",
  "descriptionPath": "$.excerpt.rendered",
  "idPath": "$.id",
  "dateFormat": "yyyy-MM-dd'T'HH:mm:ss"
}
```

只有自动探测失败、站点修改了 REST 返回结构时，才需要手写 WordPress 规则。

## 11. 怎么找到正确 JSONPath

推荐流程：

1. 用浏览器打开 API 地址。
2. 找到最外层文章数组。
3. 先写 `itemsPath`。
4. 随便挑数组里的一个对象，把它当作新的 `$`。
5. 再写 `titlePath` 和 `linkPath`。
6. 导入规则并实际添加来源测试。
7. 最后再补时间、作者、摘要、图片和稳定 ID。

例如原 JSON：

```json
{
  "result": {
    "list": [
      {
        "post": {
          "name": "标题",
          "href": "/p/1"
        }
      }
    ]
  }
}
```

规则是：

```json
"itemsPath": "$.result.list[*]",
"titlePath": "$.post.name",
"linkPath": "$.post.href"
```

## 12. 常见错误

### `itemsPath` 写到了数组本身但忘了 `[*]`

错误：

```json
"itemsPath": "$.data.items"
```

这会把整个数组当成一个 item，后续 `$.title` 无法从数组读取。

通常应该是：

```json
"itemsPath": "$.data.items[*]"
```

### 字段路径从根 JSON 重写了一遍

如果 `itemsPath` 已经定位到 `$.data.items[*]`，后续字段要从每一项开始：

```json
"titlePath": "$.title"
```

### API 浏览器能开，原读请求失败

先确认接口是否真的公开，是否依赖登录 Cookie、签名、临时 Token、验证码或浏览器 JavaScript。

JSON 规则**不会绕过登录、签名和访问控制**。

### `NEXT_DATA` / `NUXT_DATA` 找不到

检查“查看网页源代码”里是否真的存在对应 `<script>`。如果只在浏览器运行 JavaScript 以后才出现，这种内嵌 JSON 规则无法读取。

### 时间全变成同步时间

表示日期路径为空或格式解析失败。先确认 `datePath` 取出的实际值，再选择 timestamp 或正确的 `dateFormat`。

## 13. 完整 API 模板

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "id": "example-json-api",
      "name": "Example JSON API",
      "version": 1,
      "enabled": true,
      "hosts": ["example.com"],
      "sourceKind": "API",
      "endpoint": "/api/posts",
      "itemsPath": "$.data.items[*]",
      "titlePath": "$.title",
      "linkPath": "$.url",
      "datePath": "$.publishedAt",
      "authorPath": "$.author.name",
      "descriptionPath": "$.summary",
      "imagePath": "$.cover",
      "idPath": "$.id",
      "dateFormat": "yyyy-MM-dd'T'HH:mm:ssXXX",
      "maxItems": 50
    }
  ]
}
```

## 14. 完整 Next.js 模板

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "id": "example-next-data",
      "name": "Example Next.js",
      "version": 1,
      "enabled": true,
      "hosts": ["example.com"],
      "sourceKind": "NEXT_DATA",
      "endpoint": ".",
      "itemsPath": "$.props.pageProps.posts[*]",
      "titlePath": "$.title",
      "linkPath": "$.url",
      "datePath": "$.publishedAt",
      "imagePath": "$.cover",
      "idPath": "$.id",
      "maxItems": 50
    }
  ]
}
```
