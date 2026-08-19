# OrigRead

<div align="center">
  <a href="README.md">English</a> |
  <a href="README-zh-CN.md">简体中文</a>
</div>

<div align="center">
  <img src="assets/readme/logo.png" width="180" alt="OrigRead logo" />
</div>

<div align="center">
  <strong>An Android RSS reader, feed reader, news reader and personal information reader built around source-first reading.</strong>
</div>

<div align="center">
  RSS / Atom · RSSHub · Website parsing · JSON/API sources · Full-text extraction · Translation · AI summaries · OPML
</div>

<div align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" />
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" /></a>
  <img alt="Latest release" src="https://img.shields.io/github/v/release/ZGMFX01A/OrigRead?display_name=tag&sort=semver" />
  <img alt="GitHub stars" src="https://img.shields.io/github/stars/ZGMFX01A/OrigRead?style=flat" />
</div>

## What is OrigRead?

OrigRead is a source-first Android reader for people who want to follow information without turning their reading workflow into an algorithmic recommendation feed.

It keeps the mature RSS reading experience inherited from [Read You](https://github.com/ReadYouApp/ReadYou), then extends it into a broader personal information reader: when a website has RSS or Atom, OrigRead uses it; when it does not, OrigRead can discover feeds, match RSSHub routes, parse HTML lists, consume JSON/API endpoints, read WordPress REST feeds, inspect Next.js/Nuxt embedded data, or fall back to a restricted WebView for dynamic pages.

The goal is simple: **subscribe to the source, preserve the original link, extract readable content, filter noise locally, and use translation or AI only when you ask for it.**

## Why OrigRead?

- **Source-first instead of recommendation-first** — subscriptions stay under your control and articles remain tied to their original source URL.
- **More than RSS** — RSS/Atom, RSSHub, HTML website rules, automatic DOM detection, JSON/API rules, WordPress REST, Next.js/Nuxt embedded data and dynamic-page fallback can all participate in source discovery.
- **Deterministic parsing before AI** — normal source parsing, scoring and full-text extraction do not depend on an LLM.
- **AI is an optional reading tool, not the product itself** — use AI for article summaries and full-article translation without turning the app into a chat client. AI rule generation remains an unfinished experimental feature and is disabled in the current UI.
- **Local filtering before storage** — global or per-source keyword/regex rules can reject unwanted article titles before they enter the local article database.
- **Readable full text with an escape hatch** — explicit content rules, Readability, structured metadata and WebView fallback are combined with a one-tap “Read original” action.
- **Portable configuration** — subscriptions, groups, parsing rules, filters, RSSHub settings, translation settings and AI settings can be exported and restored across devices.

## Screenshots

<p align="center"><img src="assets/readme/screenshots/en-US/overview.png" width="300" alt="OrigRead overview" /></p>


| Source discovery | Reading & full text | AI summary |
| --- | --- | --- |
| <img src="assets/readme/screenshots/en-US/source-discovery.png" width="280" alt="OrigRead source discovery" /> | <img src="assets/readme/screenshots/en-US/reading.png" width="280" alt="OrigRead reading page" /> | <img src="assets/readme/screenshots/en-US/ai-summary.png" width="280" alt="OrigRead AI summary" /> |

| Translation | Parsing rules | Settings & backup |
| --- | --- | --- |
| <img src="assets/readme/screenshots/en-US/translation.png" width="280" alt="OrigRead translation" /> | <img src="assets/readme/screenshots/en-US/rules.png" width="280" alt="OrigRead parsing rules" /> | <img src="assets/readme/screenshots/en-US/settings-backup.png" width="280" alt="OrigRead settings and backup" /> |

## Documentation and other platforms

| 📖 User guide | 🖥️ Desktop edition |
| --- | --- |
| [Open the Android user guide](USER_GUIDE.md) for task-based instructions on sources, reading, AI/translation and migration. | [Open OrigRead Desktop](https://github.com/ZGMFX01A/OrigRead-Desktop) for Windows, macOS and Linux. |

## Source discovery: one URL, multiple strategies

OrigRead does not assume every website exposes the same kind of feed. When you add a source, multiple resolvers can participate and valid candidates are scored locally.

```text
Input URL
  ↓
Direct RSS / Atom
  ↓
HTML rel=alternate + common feed endpoints
  ↓
RSSHub route matching
  ↓
JSON / API / WordPress / Next.js / Nuxt
  ↓
Website parsing rules
  ↓
Automatic repeated-DOM detection
  ↓
Restricted WebView fallback for dynamic pages
  ↓
Local health checks + candidate scoring
  ↓
Best candidate by default, manual choice when needed
```

### RSS / Atom and built-in source discovery

- Direct RSS and Atom subscription.
- Automatic discovery through `<link rel="alternate">`.
- Common endpoint probing such as `/feed`, `/rss`, `/rss.xml`, `/atom.xml`, `/feed.xml` and `/index.xml`.
- A built-in discovery catalog generated from **awesome-rss-feeds** and **BestBlogs**, with 700+ deduplicated feeds and multilingual category browsing.
- OPML import and export for migration between feed readers.

### RSSHub integration without embedding RSSHub itself

OrigRead treats RSSHub as an optional route/result layer rather than embedding the Node.js service into Android.

- 5,000+ generated static and parameterized RSSHub route definitions are bundled for local matching.
- URL path/query parameters can be extracted for supported dynamic routes.
- Multiple RSSHub instances can be configured, enabled, disabled and tested independently.
- Recently successful instances are preferred; failed instances enter a short cooldown.
- RSSHub network failure is non-blocking: OrigRead continues with RSS, JSON/API or website parsing candidates.
- The app never requires Redis, Puppeteer, browserless or a local RSSHub server.

### Website parsing rules and automatic DOM detection

For sites without a usable feed, OrigRead can turn a chronological article list into a subscribable source.

- Configurable **HTML / CSS Selector website rules** based on Jsoup.
- Multiple rules for the same domain can compete as parsing candidates.
- Automatic repeated-DOM detection can discover article cards when no rule is available.
- Candidate scoring checks article count, valid titles/links, URL uniqueness, time quality and source confidence.
- Source-level parser preference is persisted and reused on later refreshes.
- Website rules support import, export, enable/disable, deletion, testing and in-app Markdown documentation.
- Parsing failures never erase previously stored articles.

### JSON/API, WordPress, Next.js and Nuxt

OrigRead also supports structured sources that are not traditional feeds.

- Configurable JSON/API rules with a deliberately restricted JSONPath subset.
- Standard public REST/JSON lists and nested arrays.
- WordPress REST API discovery, including WordPress installed in subdirectories.
- Embedded `__NEXT_DATA__`, `__NUXT_DATA__` and Nuxt data payloads.
- Relative URL completion, timestamps, common date strings, optional author/summary/image fields and HTML entity cleanup.
- JSON/API results use the same health checks and candidate scoring as RSS and website parsing.

### Dynamic pages and WebView fallback

Static parsing remains the first choice. WebView is used only as a fallback when ordinary RSS/JSON/HTML strategies cannot produce a healthy result.

- Restricted same-site navigation.
- No dangerous native JavaScript bridge.
- Bounded loading time and cleanup after parsing.
- Dynamic article-list parsing reuses the same website parser and scorer.
- Dynamic article-body extraction reuses the same full-content pipeline.
- Background bulk prefetch does not launch interactive verification pages.

OrigRead does **not** attempt to bypass login walls, CAPTCHA, paid access or website security controls.

## Full-text reading

OrigRead combines several extraction strategies instead of relying on a single parser:

- Explicit website `contentSelectors` when a rule knows the article structure.
- Readability-style general article extraction.
- JSON-LD and OpenGraph metadata for title, author, publication time and fallback content.
- HTML cleanup, unsafe-node removal and relative URL completion.
- Local quality scoring between competing content candidates.
- Restricted WebView fallback for JavaScript-rendered article bodies.
- Stable failure reasons and a one-tap **Read original** fallback.

## Reading experience

- Read / unread state.
- Starred articles.
- Archive and retention controls.
- Feed groups and article search.
- Full-content mode and original-page access.
- Text-to-speech support inherited from the reader foundation.
- Material You / Jetpack Compose interface.
- Local account mode plus optional third-party synchronization modes inherited from Read You.

## Local article filters

Noise filtering happens before new articles are saved.

- Global title keyword filters.
- Per-source title filters.
- Regular-expression rules with validation before activation.
- Rule enable/disable and deletion.
- Import/export as a standalone JSON rule set.
- Cumulative filtered-article statistics.

Existing historical articles are intentionally not deleted when a new filter is created, preventing an incorrect rule from causing destructive data loss.

## Translation: traditional providers and AI

Translation is independent from AI summaries. You can use conventional translation providers without configuring any LLM.

### Traditional translation providers

- Google ML Kit on-device translation.
- Microsoft Translator.
- DeepL.
- Google Cloud Translation.
- Self-hosted DeepLX / DLX-compatible endpoints.

The reader supports title/body translation, translated-only display, bilingual paragraph display, content-hash caching, provider selection and long-article batching.

### AI full-article translation

OpenAI-compatible models can also be selected as translation targets.

- Multiple AI providers and models.
- Strict translation prompt: no summarizing, explaining, expanding or changing the author’s position.
- Long articles are split into bounded batches.
- Stable block IDs are validated so model output cannot silently reorder or merge document sections.
- Local HTML reconstruction keeps page structure under deterministic app control.

## AI reading features

AI is optional and only runs when configured and invoked by the user.

### Multiple OpenAI-compatible providers

Each provider can keep its own:

- Display name.
- Base URL or complete Chat Completions endpoint.
- Optional API key.
- Discovered/manual model list.
- Default model.
- Enable/disable state and connection test.

This works with many OpenAI-compatible cloud services, self-hosted gateways and local model servers without coupling OrigRead to a single vendor.

### AI article summaries

- Brief, standard and detailed summary levels.
- Markdown rendering inside the reader.
- Content-hash based caching.
- Visible generation stages and elapsed time so the app does not look frozen during a non-streaming request.
- Regeneration with a temporary provider/model/summary-level choice without overwriting global defaults.
- Summary UI remains secondary to the article: the reader stays usable while the summary panel is open.

### AI-assisted parsing-rule generation

Experimental implementation work exists for AI-assisted WebsiteRule/JsonRule generation, but the end-to-end product workflow is **not finished and is disabled in the current UI**. OrigRead does not count this as a released feature until real-source coverage, failure recovery, save behavior and cross-client consistency meet the same validation standard as the deterministic parsers.

## Configuration backup and restore

OrigRead provides a versioned JSON configuration backup instead of copying the raw database.

The backup can include:

- Current-account subscriptions and groups.
- Synchronization preferences.
- Website rules and JSON/API rules.
- Article filters.
- Per-source website parser preferences.
- RSSHub instances/settings and source mappings.
- Translation providers/settings.
- AI providers, model lists and defaults.
- General user preferences, including update-check preference.

Restore uses URL-based safe merge semantics: existing subscriptions are reused, missing subscriptions are added, and extra subscriptions already present on the target device are not deleted.

API keys are **excluded by default**. If you explicitly include secrets, a backup password is required and the secret block is encrypted with PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM so it can be restored on another device without reusing device-bound Android Keystore ciphertext.

Article bodies, read/star states, AI summary caches, translation caches and temporary update state are intentionally not treated as portable configuration.

## Software updates

The GitHub build supports in-app update checking and APK installation through GitHub Releases.

- Optional “check for updates on app start” setting.
- Manual “check now” action.
- Release notes and APK asset selection.
- Download progress, retry and install flow.
- Android 8+ unknown-source authorization handled through the system settings page.

## Security and privacy design

- Normal RSS/website parsing and candidate scoring are local and deterministic.
- AI services are optional; article content is sent only when the user invokes an AI feature.
- Cloud translation services are optional; ML Kit can provide on-device translation for supported languages.
- Cloud API keys are stored with Android Keystore-backed encryption.
- Configuration backups exclude API keys unless the user explicitly opts in and supplies a backup password.
- WebView parsing does not expose a privileged JavaScript-to-Android bridge.
- OrigRead does not bypass authentication, CAPTCHA, paywalls or access controls.

## Installation

Download the latest APK from [GitHub Releases](https://github.com/ZGMFX01A/OrigRead/releases).

Current GitHub release builds target:

- Android 8.0 / API 26 or later.
- `arm64-v8a` devices.

## Build from source

Requirements:

- Android Studio with the required Android SDK.
- JDK 17.

Windows:

```powershell
.\gradlew.bat assembleGithubRelease
```

Linux / macOS:

```bash
./gradlew assembleGithubRelease
```

The project keeps the GitHub self-update dependency isolated to the GitHub flavor. F-Droid and Google Play flavors use their own distribution-safe implementations.

## Project origin and license

OrigRead is a derivative project based on [Read You](https://github.com/ReadYouApp/ReadYou).

Read You provides the original application foundation, including major parts of the Compose UI, RSS reader architecture, localization framework and existing reader behavior. OrigRead continues from that foundation with its own multi-source discovery, parsing-rule system, JSON/API sources, RSSHub integration, dynamic-page fallback, content extraction pipeline, filters, translation/AI workflows, configuration backup and GitHub update work.

Thanks to the Read You maintainers and contributors for their open-source work.

OrigRead is distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See [`LICENSE`](LICENSE).

## Links

- Repository: https://github.com/ZGMFX01A/OrigRead
- Releases: https://github.com/ZGMFX01A/OrigRead/releases
- Issues: https://github.com/ZGMFX01A/OrigRead/issues
- Desktop edition: https://github.com/ZGMFX01A/OrigRead-Desktop
- User guide: [English](USER_GUIDE.md) · [简体中文](USER_GUIDE-zh-CN.md)
- Upstream Read You: https://github.com/ReadYouApp/ReadYou

## Search keywords

Android RSS reader, RSS reader, Atom reader, feed reader, Android feed reader, news reader, personal information reader, RSSHub client, RSSHub Android, RSS discovery, RSS source discovery, OPML reader, full-text RSS, full content extraction, Readability, website parser, website feed parser, HTML parser, CSS selector parser, JSON API reader, JSONPath, WordPress REST reader, Next.js feed, Nuxt feed, WebView parser, article filter, regex filter, AI summary, article summarizer, AI translation, OpenAI compatible, DeepL, DeepLX, Google ML Kit translation, Material You, Jetpack Compose, Kotlin.

## Star History

[![Star History Chart](https://api.star-history.com/chart?repos=ZGMFX01A/OrigRead&type=timeline&logscale&legend=top-left&sealed_token=ScxwPWH-SRHVVUsck4WBlf2755xw2Yo6eLgH-FdF-jefhSw21HN0XR_b8WcTzFuACXrz59JYojVWGP8HWh492j6U7WDackC7RQPrFyoCxRCcEpQx3V8aUJRLabvGZ4fZh4eNk3_9oW4_r9uwOwEfHRnF34r6hiqClIZrk7cxASjluOISfoeehjyv4Ymx)](https://www.star-history.com/?repos=ZGMFX01A%2FOrigRead&type=timeline&logscale=&legend=top-left)
