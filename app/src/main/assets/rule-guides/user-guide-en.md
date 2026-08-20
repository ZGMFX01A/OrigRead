# OrigRead Android User Guide


Language: English. The Chinese version is available as `USER_GUIDE-zh-CN.md` in the GitHub repository.

## Quick index

- [Add your first source](#add-your-first-source)
- [Read an article](#read-an-article)
- [Use AI summaries](#use-ai-summaries)
- [Translate an article](#translate-an-article)
- [Read an article aloud](#read-an-article-aloud)
- [What to do when a source cannot be added](#what-to-do-when-a-source-cannot-be-added)
- [Manage RSSHub](#manage-rsshub)
- [Filter unwanted articles](#filter-unwanted-articles)
- [Use Website Rules and JSON/API Rules](#use-website-rules-and-jsonapi-rules)
- [Migration and backup](#migration-and-backup)
- [Accounts and sync](#accounts-and-sync)
- [Troubleshooting](#troubleshooting)

---

## Add your first source

### Add a website, RSS feed or Atom feed directly

1. From the home screen, select **+ Add** and open the add-source flow.
2. Paste a website homepage, article-list page, RSS/Atom feed or another source URL.
3. Start detection and wait for OrigRead to finish analyzing the address. The current detection stage is shown while it runs.
4. Review the candidates. OrigRead recommends a suitable option by default, while still allowing manual selection when multiple candidates are available.
5. Confirm the source and return to the timeline for the first sync.

You do not need to know in advance whether a website has RSS. For ordinary webpages, OrigRead tries the available discovery methods automatically.

### How to choose between candidate types

| Candidate | When it is useful | Typical choice |
| --- | --- | --- |
| **RSS / Atom** | The site provides a standard feed | Usually the first choice because it is stable and lightweight |
| **RSSHub** | RSSHub already has a route for the site | Convenient when the route works; one site may expose several channels |
| **JSON/API** | The site has stable public structured data | Often less fragile than page selectors when the visual layout changes |
| **Website** | There is no usable feed, but the webpage has a stable article list | Useful, though a major redesign may require a different rule |
| **Dynamic website** | The article list only appears after JavaScript runs | A final fallback and usually slower than the other options |

If several candidates work, do not worry about choosing the most “advanced” one. **A source that refreshes reliably with correct titles and links is the better source.**

### Import existing subscriptions from OPML

1. Export an `.opml` or `.xml` file from your current feed reader.
2. In OrigRead, open the add menu and choose **Import OPML**.
3. Select the file and confirm the import.
4. Review the imported groups and source count.

OPML is mainly for standard feed subscriptions. Use OrigRead configuration backup for Website/JSON rules, RSSHub settings, AI/translation configuration and other OrigRead-specific settings.

---

## Read an article

### Switch between source content, full text and the original page

These three reading modes serve different purposes:

- **Source content** — content included directly in RSS/Atom/JSON. Some feeds provide only a short summary.
- **Full text** — readable article text extracted after OrigRead fetches the article page.
- **Original page** — the real website. Use it when extracted text is incomplete or you need comments, charts or interactive content.

If a feed provides only a summary, switch to full text. If full-text extraction is still incomplete, open the original page.

### Read state, starred state and navigation

From the reader you can:

- mark an article read or unread;
- star or unstar it;
- move to the previous or next article;
- search within the current article;
- open the original webpage.

None of these basic reading actions requires AI.

---

## Use AI summaries

### Configure an AI service first

1. Open **Settings → AI Reading**.
2. Add an OpenAI-compatible provider.
3. Enter the service endpoint and, when required, an API key.
4. Fetch or enter models and choose a default model.
5. Use **Test connection** to verify the configuration.
6. Save and enable the provider.

You can configure several providers. The default only controls what a normal tap uses; it does not prevent temporary switching.

### Generate a summary for the current article

1. Open an article.
2. **Tap the AI button**.
3. OrigRead starts with the current default provider, model and summary mode.
4. The UI shows the processing stage and elapsed time.
5. If you no longer want the result, choose **Stop summary generation**.

Successful summaries are cached. If the article text changes, OrigRead does not keep treating the old summary as a summary of the new text.

### Temporarily use another model or summary mode

**Long press the AI button.**

The long-press menu lets you choose, for this generation only:

- AI provider;
- model;
- brief, standard or detailed summary mode.

This is useful when you occasionally want another model without changing global defaults.

---

## Translate an article

### Configure translation first

Open **Settings → Translation settings**, enable the translation providers you want and choose a default target language.

Traditional translation and AI translation are independent from AI summaries. You can use traditional providers without configuring an LLM.

### Use the default translation method

1. Open an article.
2. **Tap the translation button**.
3. OrigRead uses the current default method and target language.
4. After translation, switch between translated content or the available original/bilingual presentation.

### Temporarily switch the translation method or target

**Long press the translation button.**

Long press opens the available translation choices for this article. You can use a different enabled method/target temporarily, and make it the new default when needed.

If no useful choice appears, return to **Settings → Translation settings** and make sure at least one provider and a valid target language are enabled.

---

## Read an article aloud

- **Tap the read-aloud button** to start or stop text-to-speech.
- **Long press it while idle** to open Android system TTS settings when that system page is available.

When translated text is the main content being displayed, read-aloud follows the content you are currently reading. The AI summary panel has its own read-aloud control.

---

## What to do when a source cannot be added

### Check which stage is still running

The add-source UI shows whether OrigRead is checking RSS/Atom, RSSHub, JSON/API, static website parsing or a dynamic page.

If one stage is slow, allow it to finish instead of repeatedly closing and reopening the dialog.

### What “dynamic WebView fallback” means

Some websites do not include the article list in the initial HTML. The list appears only after JavaScript runs. When normal parsing cannot see those articles, OrigRead can automatically try a **WebView-rendered page** as the last fallback.

You do not need to enable this step manually.

This is different from **Retry with browser rendering** in AI Website Rule generation. Source discovery uses its dynamic fallback automatically; AI rule generation only starts a browser-rendered retry when the page shows that button and you choose it. Background refreshes are not sent through this heavier path.

If the rendered page produces real article links but the result looks less reliable, OrigRead may warn that the source might not be handled correctly. In that case:

1. Check whether the candidate shows sensible article titles and a reasonable article count.
2. If it looks valid, you can try adding it.
3. Watch one or two later refreshes to see whether it remains stable.
4. If it repeatedly fails, remove it and prefer RSS, RSSHub or a stable rule instead.

**A page merely loading is not enough.** If WebView cannot extract usable article links, OrigRead does not create an empty source with zero articles.

### The site works in a browser but OrigRead still cannot add it

Common reasons include:

- login is required;
- CAPTCHA or browser challenges are present;
- content only appears after complex interaction;
- there is no stable article-list structure;
- the site restricts automated requests;
- the current network cannot reach the relevant RSSHub/API service.

OrigRead does not bypass login walls, CAPTCHA, paywalls or website access controls. In these cases, prefer an official feed, an RSSHub route or a public API when available.

---

## Manage RSSHub

### Configure RSSHub instances

Open **Settings → RSSHub**.

You can:

1. enable or disable RSSHub;
2. use bundled instances;
3. add a public or self-hosted instance;
4. test an instance individually;
5. remove instances you do not want.

### Why a route can be “matched” but still not subscribable

OrigRead first matches the URL against its local RSSHub route catalog, then tries a configured RSSHub instance. Therefore:

**Matched route ≠ the current instance can successfully generate the feed.**

Typical states mean:

- **Available** — the route works and the returned feed passed validation;
- **Instance timed out / unreachable** — the route exists, but the current instance failed;
- **No valid feed returned** — the instance responded but not with a usable feed;
- **Feed content failed quality checks** — content was returned but is not suitable for the timeline;
- **More specific page URL required** — the route needs parameters that are missing from the current URL.

Public instances can be unstable, so retrying later or switching instances may be enough.

---

## Filter unwanted articles

Article filters are useful when you want to keep a source but never want certain titles in your normal timeline.

Depending on the rule type, you can use:

- title keywords;
- regular expressions;
- global or source-specific scope.

Filtering happens **before newly fetched articles enter the normal timeline**. Creating a new filter does not retroactively delete historical articles.

---

## Use Website Rules and JSON/API Rules

### When to use a Website Rule

Use a Website Rule when the site has no stable feed but its article list is present directly in relatively stable HTML.

Website Rules identify article cards, titles, links, dates and other fields from the page. A major site redesign can invalidate selectors.

### When to use a JSON/API Rule

Use a JSON/API Rule when the site exposes a stable public REST/JSON or another structured endpoint. A stable API is often less affected by visual redesigns than page selectors.

### Manage rules

Rule pages support import, export, enable/disable, testing and deletion, with more detailed format documentation available inside the app.

### Generate a Website Rule with AI

AI generation is useful when a site has no ready-made feed and you want a rule candidate that can be tested. It is not a promise that every site can become a stable subscription: redesigns and access restrictions still matter.

1. Open **Settings → Website Rules → Generate Website Rule with AI**.
2. Enter the page that contains the article list: for example, a news home page, category page or search-results page. Do not start with one article's detail page.
3. The dialog is pre-filled with the default AI provider and model from AI settings. Confirm them before starting, or temporarily switch to another enabled provider.
4. Tap **Generate**. The dialog reports fetching, source analysis, candidate generation and local-parser validation as separate stages.
5. Review the article count, score and sample titles. Save only when the candidate clearly contains the articles you want to subscribe to.

The first attempt uses a normal web request because it is faster and uses fewer resources. If the page needs JavaScript or the normal request is blocked, the result shows the failed stage, a user-facing reason and **Retry with browser rendering**. Choose it only then. OrigRead will perform one controlled browser-rendered retry; it may take longer and can still fail.

OrigRead does not bypass login, CAPTCHA, paywalls or other access controls. A page opening in your browser does not mean the app can generate a rule without the same access.

### Why the content rule is a separate result

List and article-content rules are deliberately handled in two stages:

- the list stage finds titles, links and dates and must pass a real local run first;
- the content stage visits a few detail pages, asks the model for a content region and validates it with the local extractor;
- if content generation or validation fails, the list rule can still be saved. When opening an article, OrigRead continues with generic extraction and can fall back to the built-in WebView when appropriate.

“Content rule verified” means the selector worked on the sampled article pages. “Not generated” or “Generation failed” means only the content enhancement was unavailable; it does not invalidate a list rule that already passed. Login pages, CAPTCHA, cross-site article links and anti-bot restrictions can prevent a reusable content rule from being generated.

### Generate a JSON/API Rule with AI

Open AI generation from **Settings → JSON/API Rules** and enter a public JSON/API endpoint or a page containing embedded JSON. Choose a provider and model, start generation, review the detected source type, article count, score and samples, then save the candidate.

The JSON content stage only adds a content path when the returned data contains a reusable, validated field. Missing content fields or a failed path check do not block saving the list rule. JSON generation does not automatically open a browser when the list request fails and does not bypass login or CAPTCHA.

When generation fails, read the reported stage and reason first. Then decide whether to switch models, use a different endpoint, or use the rule template and configure it manually. The error message is for deciding what to do next, not a developer stack trace you need to understand.

If you are an ordinary user, you do not need to learn rule syntax before adding common sites. Try automatic source discovery first and only reach for rules when the automatic methods are insufficient.

---

## Migration and backup

### OPML or OrigRead configuration backup?

| Goal | Use |
| --- | --- |
| Move standard feed subscriptions to/from another RSS reader | **OPML** |
| Move OrigRead-specific configuration between Android and Desktop | **Configuration backup** |
| Move Website/JSON rules, filters, RSSHub, AI/translation settings | **Configuration backup** |
| Export only standard RSS subscriptions | **OPML** |

### Export configuration

Open **Settings → Backup & restore** and choose configuration export.

Configuration backup is mainly for settings and subscriptions. Article bodies, read/star history, generated summaries, translation caches and other runtime caches are not treated as portable configuration.

API keys are excluded by default. If you explicitly choose to include API keys, set a backup password and use the same password when restoring on another device.

---

## Accounts and sync

### Local account

Use Local when you mainly want OrigRead-specific RSSHub, Website, JSON/API, rule and local-reading features.

### FreshRSS / Google Reader Compatible / Fever Compatible

These account types connect to servers that expose the corresponding protocols. What a remote account can do depends on the capabilities of that protocol and server.

Website, JSON/API and RSSHub are OrigRead-specific source types, so they belong to Local accounts rather than being presented as native remote-server features.

If you do not need server-based cross-device sync, you do not need to create a remote account just to get “more features”.

---

## Software updates

The GitHub build can check OrigRead Android Releases inside the app and download an APK for the current device.

On Android 8 and later, the first install initiated from the app may ask you to allow installs from this source. That is an Android system permission screen, not an OrigRead account or data permission.

---

## Troubleshooting

### A website returns 403 or 418

The site may distinguish ordinary programmatic requests from browser traffic or restrict requests by rate, region or network. OrigRead tries normal parsing within its security boundaries but does not bypass access controls.

### RSSHub says “matched” but nothing is subscribable

“Matched” only means the local route was found. Read the status: instance timeout, unreachable instance, invalid feed or quality rejection can all make the current result unavailable.

### The AI button is unavailable

Open **Settings → AI Reading** and make sure at least one provider is enabled, a model is selected and the connection test succeeds.

### Long pressing translation shows no useful choices

Open **Settings → Translation settings**, enable at least one translation provider and verify the target language.

### Full-text extraction is incomplete

Open the original page and check whether the site itself shows the missing content. Pages that depend on login, complex interaction or unusual scripts may not be fully extractable; the original page remains the final fallback.

### Dynamic sources refresh slowly

Dynamic sources must start WebView, wait for page scripts and parse the rendered page, so they are naturally slower than standard RSS. Prefer RSS, RSSHub or a stable API when one is available.

---

## Other platforms

🖥️ **OrigRead Desktop for Windows, macOS and Linux**: https://github.com/ZGMFX01A/OrigRead-Desktop
