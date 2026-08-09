# Website Parsing Rule Guide

Website parsing rules are used for article-list pages where the **server already returns complete HTML**. OrigRead parses that HTML with Jsoup and does not execute page JavaScript just because a rule exists.

If a site already provides RSS, a JSON API, or can be handled well by automatic DOM detection, you usually do not need to write a rule manually. Custom rules are mainly useful when automatic results are poor or when you want a stable parsing strategy for long-term use.

## 1. Understand the three parsing levels first

A rule is applied in this order:

```text
Whole list page
  ↓ articleSelectors
Find each “article card”
  ↓ titleSelector / linkSelector / dateRules / imageSelector
Read title, link, date, and image from each card
  ↓ user opens an article detail page
contentSelectors
  ↓
Extract article content; fall back to generic extraction if needed
```

The most common mistake is forgetting that `titleSelector`, `linkSelector`, `dateRules.selector`, and `imageSelector` are all evaluated **relative to a single node matched by articleSelectors**, not against the whole page again.

## 2. Minimal working example

Suppose the page contains:

```html
<div class="news-list">
  <article class="news-item">
    <a class="title" href="/news/123">New product released</a>
    <time>2026-08-08 12:30</time>
    <img data-src="/images/123.jpg">
  </article>
</div>
```

The corresponding rule is:

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "id": "example-news",
      "name": "Example News",
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

## 3. Top-level file structure

An imported file must be a rule package:

```json
{
  "schemaVersion": 1,
  "rules": []
}
```

- `schemaVersion`: must currently be `1`.
- `rules`: may contain multiple rules.
- Importing another rule with the same `id` replaces the existing one.
- Unknown fields are ignored, but do not rely on this behavior to store custom data.

## 4. Basic fields

### `id`

The unique ID of the rule, for example:

```json
"id": "ithome-home"
```

Use lowercase letters, numbers, and hyphens where possible, and keep the ID stable over time.

### `name`

The display name shown on the Website Parsing Rules page.

### `version`

The rule's own version number, defaulting to `1`. You can increase it after changing the rule to make manual maintenance easier.

### `enabled`

Whether the rule participates in matching. When set to `false`, the rule remains stored but is not used.

### `hosts`

The domains this rule applies to:

```json
"hosts": ["example.com"]
```

Use **domain names only**. Do not use:

```text
https://example.com
example.com/news
https://example.com/news
```

`example.com` also matches subdomains such as `www.example.com` and `news.example.com`.

## 5. Find article cards with `articleSelectors`

This is the most important field.

```json
"articleSelectors": [
  ".news-list .news-item",
  "main article"
]
```

It uses **Jsoup CSS Selector** syntax.

OrigRead tries selectors in order and uses the **first selector that matches nodes**. Results from multiple selectors are not merged.

Common examples:

```text
.news-list li
ul.article-list > li
main article
#content .post-card
div[data-type=article]
```

Ideally, one matched node should represent exactly one article card. Avoid selecting the whole list container or every `a` element on the page.

## 6. Title: `titleSelector`

Find the title inside each article card:

```json
"titleSelector": "h2 a"
```

OrigRead reads the visible `.text()` of the matched element.

For example:

```html
<article class="item">
  <h2><a href="/123">The real article title</a></h2>
  <p>Summary...</p>
</article>
```

Recommended:

```json
"articleSelectors": ["article.item"],
"titleSelector": "h2 a"
```

Do not use the whole `article.item` as `titleSelector`, or summary, author, and other text may be concatenated into the title.

## 7. Link: `linkSelector` and `linkAttribute`

The common configuration is:

```json
"linkSelector": "h2 a",
"linkAttribute": "href"
```

OrigRead reads the element's absolute URL, so a relative URL such as:

```text
/news/123
```

is resolved automatically to:

```text
https://example.com/news/123
```

If the link is not on the title element, specify a different element.

For example:

```html
<article class="item" data-url="/news/123">
  <h2>Title</h2>
</article>
```

you may use a separate link element when one exists:

```json
"titleSelector": "h2",
"linkSelector": ".item-link",
"linkAttribute": "href"
```

The current rule model still requires `linkSelector` to select a real element. Do not confuse CSS attribute expressions with selectors.

## 8. Publication time: `dateRules`

You may provide multiple date extraction strategies:

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

OrigRead tries them in order, reads the selected element's text, and parses it with Java `SimpleDateFormat`.

Common patterns:

```text
yyyy-MM-dd HH:mm:ss
yyyy-MM-dd HH:mm
yyyy/MM/dd HH:mm
yyyy-MM-dd
MM-dd
HH:mm
```

`HH:mm` automatically uses the current fetch date. `MM-dd` automatically uses the current year. If every date rule fails, the current fetch time is used.

Rules generated by automatic DOM detection may contain:

```json
"automaticDateExtraction": true
```

This is an internal automatic-detection field. Manually written rules normally should not set it.

## 9. List images: `imageSelector` and `imageAttributes`

For example:

```html
<img data-original="/cover.jpg" src="placeholder.jpg">
```

Rule:

```json
"imageSelector": "img",
"imageAttributes": ["data-original", "data-src", "src"]
```

OrigRead checks `imageAttributes` in order and uses the **first non-empty URL**. Relative URLs are resolved automatically.

If the site has no image on the list page, you can omit `imageSelector`.

## 10. Article content: `contentSelectors`

This field is not used on the list page. It is evaluated when the user opens an article detail page and OrigRead fetches full content.

```json
"contentSelectors": [
  "article .article-content",
  "main .post-content",
  "article"
]
```

OrigRead uses the first candidate that **exists and contains text**. Explicit content selectors are tried before generic Readability extraction.

If every selector fails or `contentSelectors` is empty, OrigRead still tries structured data, Readability, and the dynamic-page fallback when necessary.

If you are unsure, it is better to omit this field than to use an overly broad selector such as `body`.

## 11. Filter links with `includeUrlRegex`

If a list mixes article, category, topic, and author links, use a regex to keep only article URLs:

```json
"includeUrlRegex": "^https?://(?:www\\.)?example\\.com/news/\\d+(?:\\?.*)?$"
```

Remember that backslashes inside JSON strings must be escaped as `\\`.

The regex is matched against the **resolved absolute article URL** with full `Regex.matches()`, so `^...$` is usually appropriate.

## 12. Filter titles with `excludeTitleRegexes`

```json
"excludeTitleRegexes": [
  ".*advertisement.*",
  "(?i).*sponsored.*"
]
```

An article matching any expression is discarded.

## 13. `maxItems`

Maximum number of articles kept per fetch:

```json
"maxItems": 50
```

The valid range is `1` to `200`.

## 14. `cleanupMode` and `urlIdRegex`

For normal rules, keep:

```json
"cleanupMode": "NONE"
```

`URL_ID_RANGE` is a special cleanup mode for sites whose article URLs contain monotonically increasing numeric IDs, for example:

```json
"cleanupMode": "URL_ID_RANGE",
"urlIdRegex": "/(\\d+)\\.htm"
```

After synchronization, OrigRead can use the oldest ID seen in the current fetch to remove incorrectly collected old items that fall outside the valid range. Starred articles are not removed automatically.

If you are not certain that the site's IDs increase reliably, **do not enable this mode**.

## 15. Do not manually write `automatic*` fields

These fields are primarily generated and maintained by OrigRead's automatic DOM detector:

```text
automaticUrlPattern
automaticDateExtraction
automaticRegionScore
```

Manual rules normally omit them. Incorrect values may filter out valid articles.

## 16. Recommended workflow

1. Open the target list page in a desktop browser.
2. Press F12 to open developer tools.
3. Find one normal article card and identify its common parent node.
4. Start with only `articleSelectors + titleSelector + linkSelector`.
5. Use “Test website parsing rule” in OrigRead to confirm that correct articles are returned.
6. Add date, image, and URL regex fields afterward.
7. Open one article detail page and add `contentSelectors` last.
8. Keep the rule enabled only after testing succeeds.

> Do not fill every field from the start. Simpler rules are less likely to break after minor website changes.

## 17. Common mistakes

### No articles are parsed

Check `articleSelectors` first. The selector must exist in the **static HTML returned by the server**.

If developer tools show the node but “View page source” does not, the site is probably rendered dynamically with JavaScript. A static rule may not work; let OrigRead use its dynamic-page fallback instead.

### The title becomes a large block of text

`titleSelector` is too broad. Narrow it to the real `h1/h2/h3/a.title` element.

### The link is empty

Verify that `linkSelector` selects an element containing the URL attribute and that `linkAttribute` really is `href` or the intended attribute.

### Category, author, or tag links are mixed in

First narrow `articleSelectors`. If unwanted links remain, add `includeUrlRegex`.

### Every article gets the sync time

`dateRules` did not parse successfully. Check the raw text returned by the selector, then verify the `SimpleDateFormat` pattern.

### Extracted content contains navigation or the entire page

`contentSelectors` is too broad. Select the real article body instead of outer containers such as `body` or a very broad `main`.

## 18. Complete template

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
      "excludeTitleRegexes": [".*advertisement.*"],
      "maxItems": 50,
      "cleanupMode": "NONE"
    }
  ]
}
```
