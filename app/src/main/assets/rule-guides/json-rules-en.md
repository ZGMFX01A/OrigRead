# JSON / API Rule Guide

JSON rules are intended for sites whose **article list is already available as JSON data**, including public REST APIs, WordPress REST, and JSON embedded in static HTML by Next.js or Nuxt.

They are not rules for finding articles in HTML with CSS selectors. The difference is:

```text
Website parsing rule: HTML → CSS Selector → articles
JSON rule: JSON → JSONPath → articles
```

When a page has a stable public API behind it, a JSON rule is usually more reliable than a CSS rule.

## 1. Minimal working example

Endpoint:

```text
https://api.example.com/v1/posts
```

Response:

```json
{
  "data": {
    "items": [
      {
        "id": 123,
        "title": "New product released",
        "url": "/news/123",
        "publishedAt": "2026-08-08T12:30:00+08:00",
        "author": { "name": "Author A" },
        "summary": "Article summary",
        "cover": "/images/123.jpg"
      }
    ]
  }
}
```

Rule:

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

## 2. Top-level rule file

```json
{
  "schemaVersion": 1,
  "rules": []
}
```

- `schemaVersion` must currently be `1`.
- `rules` may contain multiple rules.
- Importing a rule with an existing `id` replaces the old rule.

## 3. `sourceKind`: first decide where the JSON comes from

Three source kinds are currently supported:

```text
API
NEXT_DATA
NUXT_DATA
```

### `API`

OrigRead requests `endpoint` directly, and the response body must be JSON.

```json
"sourceKind": "API",
"endpoint": "/api/posts"
```

`endpoint` may be an absolute URL or a path relative to the URL entered by the user.

For example, if the user adds:

```text
https://example.com/news/
```

and the rule contains:

```json
"endpoint": "/api/posts"
```

the final request resolves to:

```text
https://example.com/api/posts
```

### `NEXT_DATA`

For Next.js pages. OrigRead requests the HTML page entered by the user and reads:

```html
<script id="__NEXT_DATA__" type="application/json">...</script>
```

A typical rule uses:

```json
"sourceKind": "NEXT_DATA",
"endpoint": "."
```

No additional API request is made. `itemsPath` and the other paths target JSON inside `__NEXT_DATA__`.

### `NUXT_DATA`

For Nuxt pages. OrigRead supports:

```html
<script id="__NUXT_DATA__" type="application/json">...</script>
```

and:

```html
<script type="application/json" data-nuxt-data>...</script>
```

A typical rule also uses:

```json
"sourceKind": "NUXT_DATA",
"endpoint": "."
```

> NEXT_DATA / NUXT_DATA only read JSON already present in the HTML returned by the server. They do not execute page JavaScript.

## 4. `hosts`

```json
"hosts": ["example.com"]
```

Use domain names only, without protocol or path.

`example.com` also matches its subdomains.

For example, a rule with `hosts: ["example.com"]` also matches `www.example.com`.

## 5. OrigRead supports a deliberately limited JSONPath subset

To keep parsing predictable and lightweight, OrigRead does not include a full JSONPath engine.

Supported forms include:

```text
$.data.items
$[0]
$.items[0]
$.items[*]
$.items[*].title
$.author.name
```

Every path must start with `$`.

The following are **not currently supported**:

```text
$..title                  recursive descent
$.items[?(@.type=='a')]   filters
$.items[0:10]             slices
$.items[0,2]              unions
$['strange-key']          bracket string fields
complex script expressions
```

If an API requires these complex expressions, it is usually better to find a more direct endpoint than to put complex query logic into a rule.

## 6. `itemsPath`: locate the article array first

This is the most important JSONPath.

Given:

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

use:

```json
"itemsPath": "$.data.items[*]"
```

After `itemsPath` is evaluated, each `{ "title": ... }` object becomes one candidate article.

Paths such as `titlePath` and `linkPath` are then evaluated **relative to that item, starting from `$` again**.

So use:

```json
"titlePath": "$.title"
```

not:

```json
"titlePath": "$.data.items[*].title"
```

## 7. `titlePath` and `linkPath`

Both fields are required.

```json
"titlePath": "$.title",
"linkPath": "$.url"
```

If either the title or link is empty, that item is skipped.

If a WordPress-style title contains HTML in a `rendered` field:

```json
"title": { "rendered": "Hello &amp; <b>World</b>" }
```

use:

```json
"titlePath": "$.title.rendered"
```

OrigRead converts it to readable plain text:

```text
Hello & World
```

Relative article URLs are resolved automatically against the current API or page URL.

## 8. Optional fields

### `datePath`

```json
"datePath": "$.publishedAt"
```

Numeric timestamps are supported:

```text
1723089600       seconds
1723089600000    milliseconds
```

Integers below `10000000000` are treated as seconds; larger values are treated as milliseconds.

String dates are also supported. OrigRead tries, in order:

```text
the configured dateFormat
yyyy-MM-dd'T'HH:mm:ssXXX
yyyy-MM-dd HH:mm:ss
yyyy-MM-dd
```

If every attempt fails, the current fetch time is used.

### `dateFormat`

If the server uses a non-default date format:

```json
"datePath": "$.pubDate",
"dateFormat": "yyyy/MM/dd HH:mm"
```

The format follows Java `SimpleDateFormat`.

### `authorPath`

```json
"authorPath": "$.author.name"
```

HTML tags and entities are removed automatically from author text.

### `descriptionPath`

```json
"descriptionPath": "$.summary"
```

Summary HTML may be preserved here; the reader handles it later through the normal content pipeline.

### `imagePath`

```json
"imagePath": "$.cover"
```

Relative image URLs are resolved automatically.

### `idPath`

```json
"idPath": "$.id"
```

This is used to create a stable article ID. If it is empty, OrigRead falls back to the article link. A stable ID is therefore recommended when available, but it is not required.

## 9. `maxItems`

```json
"maxItems": 50
```

The valid range is `1` to `200`.

Duplicate article URLs are removed before the item limit is applied.

## 10. You usually do not need to write a WordPress rule manually

For the standard WordPress REST API:

```text
/wp-json/wp/v2/posts?_embed=1&per_page=30
```

OrigRead already includes automatic detection, including WordPress installations in subdirectories.

Its core structure is roughly equivalent to:

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

You generally need a custom WordPress rule only when automatic detection fails or the site has changed the REST response structure.

## 11. How to find the correct JSONPath

Recommended workflow:

1. Open the API URL in a browser.
2. Find the outermost array containing articles.
3. Write `itemsPath` first.
4. Pick one object in that array and treat it as the new `$`.
5. Write `titlePath` and `linkPath` relative to that object.
6. Import the rule and test it by actually adding the source.
7. Add time, author, description, image, and stable ID fields last.

For example, given:

```json
{
  "result": {
    "list": [
      {
        "post": {
          "name": "Title",
          "href": "/p/1"
        }
      }
    ]
  }
}
```

the paths are:

```json
"itemsPath": "$.result.list[*]",
"titlePath": "$.post.name",
"linkPath": "$.post.href"
```

## 12. Common mistakes

### `itemsPath` points to the array but omits `[*]`

Incorrect:

```json
"itemsPath": "$.data.items"
```

This treats the whole array as one item, so a later `$.title` cannot read a title from it.

Usually use:

```json
"itemsPath": "$.data.items[*]"
```

### Field paths repeat the root JSON path

If `itemsPath` already points to `$.data.items[*]`, later fields start from each individual item:

```json
"titlePath": "$.title"
```

### The API opens in a browser but OrigRead cannot request it

Check whether the endpoint is actually public or whether it depends on login cookies, request signatures, temporary tokens, CAPTCHA, or browser JavaScript.

JSON rules **do not bypass login, signatures, or access controls**.

### `NEXT_DATA` / `NUXT_DATA` cannot be found

Use “View page source” and verify that the corresponding `<script>` is really present. If it only appears after browser JavaScript runs, the embedded JSON rule cannot read it.

### Every article gets the sync time

This means the date path is empty or parsing failed. Check the actual value returned by `datePath`, then choose either timestamp handling or the correct `dateFormat`.

## 13. Complete API template

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

## 14. Complete Next.js template

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
