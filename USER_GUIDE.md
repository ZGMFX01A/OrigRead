# OrigRead Android User Guide

[Back to the project](README.md) · [简体中文](USER_GUIDE-zh-CN.md)

This guide covers **OrigRead** and **OrigRead X**. Features are the same, with slightly different defaults. Existing users can keep updating the app they already use.

## 1. Start here

Start with a source you want to read, open an article, and try full text. Set up AI when you want help understanding it, and translation when you need another language. Add other tools as they become useful.

Jump to what you want to do:

- [2. Add sources](#2-add-sources-give-origread-the-url)
- [3. Read articles](#3-reading-source-content-full-text-and-original)
- [4. Configure AI](#4-configure-ai-get-the-provider-working-first)
- [5. Summaries and article chat](#5-summaries-and-the-ai-reading-assistant)
- [6. Citation](#6-citation-the-reference-can-take-you-back-to-the-evidence)
- [7. Multi-article context, search and tools](#7-add-more-context-only-when-you-need-it)
- [8. Translation, sharing and TTS](#8-translation-sharing-and-tts)
- [9. When source discovery fails](#9-when-adding-a-source-fails)
- [10. Website / JSON rules](#10-when-to-use-website-rule--json-api-rule)
- [11. Filters](#11-filter-unwanted-articles)
- [12. Backup, edition sync and accounts](#12-backup-edition-sync-and-accounts)
- [13. Updates](#13-software-updates)
- [14. FAQ](#14-faq)

---

## 2. Add sources: give OrigRead the URL

A subscription does not have to start with an RSS URL. Give OrigRead a website home page or section page and it will look for usable article sources. Sites without a ready-made feed may still be accessible through RSSHub, article lists, or public APIs.

### 2.1 Add your first source

1. On Home, tap **+ Add** and paste a website, section, RSS / Atom, or public API URL.
2. Start discovery and wait for the results. The screen shows which source type is being checked.
3. Preview the articles, check their titles and links, and choose the result that matches the section you want.
4. Confirm and return to the timeline for the first sync.

For a particular column or topic, try its list page first. A single article URL may not reveal all updates from that section. To find something new, browse **Discover sources** by name, category, or keyword.

### 2.2 Where OrigRead can find articles

| Source type | What it can follow | What to check |
| --- | --- | --- |
| **RSS / Atom** | Standard feeds, including feed addresses discovered in website pages | Usually a good choice when the content and updates are correct |
| **RSSHub** | Websites, channels, or sections supported by an existing route | Whether it is the right section and the configured instance returns content |
| **Website** | Regular article lists on sites without feeds | Correct titles and article links, without navigation or ads mixed in |
| **JSON / API** | Public article APIs, WordPress data, and data embedded in some Next.js / Nuxt pages | Whether the returned records are the articles you want |
| **Dynamic page** | Article lists that appear only after the page loads and runs scripts | Article count and preview after rendering, plus any low-confidence notice |

One URL may produce several results. OrigRead checks article counts, titles, links, and dates to rank them. The recommendation is a starting point; the preview tells you whether it contains what you want. Your choice is saved with the source for future refreshes.

A stable feed or public API is often a good first choice. Website parsing and dynamic rendering make more sites possible to follow. If one method fails, discovery can try other available methods, so you do not need to classify the URL yourself.

### 2.3 Set up an RSSHub instance

A route describes how to get articles from a website. An instance is the service that actually retrieves them. Finding a route still requires a working instance to return its feed.

Open **Settings → RSSHub**, add or enable an accessible instance, and test its connection. You can configure several instances or use your own RSSHub service; you do not need to run an RSSHub server on the phone. If a route matches but returns no content, check the instance as described under [source troubleshooting](#9-when-adding-a-source-fails).

### 2.4 Bring subscriptions from another reader

Export OPML from the old reader, then choose **Import OPML** in OrigRead's add-source flow to bring in standard feeds and groups.

Website rules, JSON rules, RSSHub instances, translation, and AI settings need a [full configuration backup](#12-backup-edition-sync-and-accounts). OPML cannot carry those settings.

---

## 3. Reading: source content, full text and original

Open an article in the timeline to start reading. If it is too short, lacks images, or you want to read the comments, these three views help:

| Content | When to use it |
| --- | --- |
| **Source content** | The text supplied by the feed or API; it may be complete or just an excerpt |
| **Full text** | The article body extracted from its web page, useful for continuous reading, translation, and AI analysis |
| **Original** | The original website, including comments, interactive charts, and anything extraction missed |

If the source supplies only a few lines, try fetching full text. Open the original if the result is still incomplete. The first extraction can take a moment; following a citation into an unloaded article may also need time to fetch its body.

The reader lets you mark articles read, star them, move to the previous or next article, search within the text, and adjust the reading appearance. Phones mainly use one column. Tablets and larger screens can keep lists, the article, or AI in view together as space allows.

---

## 4. Configure AI: get the provider working first

To generate summaries or discuss an article, first connect a service in **Settings → AI Reading**.

1. Add an OpenAI-compatible service and enter its **Base URL** or complete API endpoint.
2. Supply an **API key** if required, then fetch the model list or enter a model name manually.
3. Select the default model and run **Test connection**.
4. Save and enable the service, then return to an article and try a summary.

Use the address, key, and model name supplied by your provider. You can add several services and switch for an individual summary or conversation without repeatedly changing the defaults.

### 4.1 Choose what the AI button opens

The **AI conversation assistant** and **Reader summary** switches control the main AI button:

| Conversation assistant | Reader summary | Main AI button |
| --- | --- | --- |
| Off | No setting needed | Generates a summary |
| On | On | Prefers summaries, with article chat also available |
| On | Off | Opens article chat; an empty conversation still offers quick summaries |

Reader summary is shown only when the conversation assistant is enabled. Turning the assistant off hides chat, search, MCP, and Skills. Conversations and settings remain saved and become available when you turn it back on.

---

## 5. Summaries and the AI reading assistant

### 5.1 Start with a summary

Open the summary from the reader and choose **Quick / Balanced / Deep**. Progress stages and elapsed time appear during generation. Long articles or slower models may take a little longer.

Successful summaries are saved for reuse while the article content is unchanged. To try a different depth or model, choose the service, model, and summary mode again; the temporary choice does not change your defaults. If the main button opens chat, use the quick summary actions in an empty conversation.

### 5.2 Ask with the article in view

Enable the conversation assistant, then open **Ask this article** from the AI panel or set the main button to open chat directly. Try “What is the author's main evidence?” or “Does this paragraph contradict the earlier argument?”

To ask about a particular passage, select it in the reader and use the AI action from the selection menu. The answer draws on the original article. Use Citation, described next, to check its evidence.

### 5.3 Continue a discussion

Conversations are saved per article. Create, switch, rename, or delete them as needed, and change the service or model near the composer. You can stop generation, copy an answer, or regenerate it. Reasoning is displayed when the model supplies it and the display setting is enabled.

---

## 6. Citation: the reference can take you back to the evidence

Citation lets you move between an answer and the original text. Check the passage behind a conclusion, then return to the answer and keep reading.

### 6.1 Follow a citation to the evidence

1. Tap a reference such as `[1]` or `[2]` in the answer.
2. If it contains several sources, choose the one you want to inspect.
3. OrigRead opens the article, locates the cited text, and highlights it. Read the surrounding passage to judge whether it supports the answer.

Evidence can come from the current article or an attached one. If full text has not loaded, wait for it to appear; you do not need to leave the conversation and find the article yourself.

### 6.2 Return from the article to the answer

Tap a Citation marker in the article to return to **the conversation and answer that cited it**, at the relevant paragraph.

Try adding two reports to one conversation and asking, “How do they explain the cause of this event differently?” Follow a citation into the first report, return to the answer, then check the second. You can keep checking and asking questions within the same discussion even when the evidence spans several articles.

### 6.3 Take over the scrolling

Drag the page to read on your own while a citation is scrolling into view. OrigRead cancels that automatic navigation. Browse the surrounding text freely, and tap the citation again if you want to locate it once more.

### 6.4 Where different references lead

Article citations lead to text in the reader. Web search citations retain their website sources, and tool results retain their tool sources.

If a website has rewritten an article or an old reference can no longer be matched precisely, OrigRead offers the source for inspection. Citations make evidence easier to check; the original text still matters when judging AI's interpretation.

---

## 7. Add more context only when you need it

### 7.1 Compare several articles

Tap **+** in the reading assistant and select related articles from recent items or title search. You can add up to **5** additional articles. Then ask something like “Compare the points these articles agree and disagree on.”

Only the additional articles you select are supplied with your question. The answer's context view shows the articles, search results, and tool results actually used. Some content may be shortened or omitted when the combined material exceeds the length budget.

### 7.2 Look up background or recent developments

Configure a search service in **Settings → AI Reading → Web Search**, supply its required key and endpoint, and test it first.

| Search mode | When to use it |
| --- | --- |
| **Off** | Discuss only the supplied material |
| **Auto** | Ask about recent developments or explicitly request online research |
| **Force next message** | Require a search for the next question, then restore the previous mode |

The answer shows its search activity and results so you can inspect the queries and sources.

### 7.3 Connect your own tools

MCP connects external tools; ordinary reading and article chat do not need it. Open **Settings → AI Reading → MCP**, add a server address, configure authentication as the provider requires, then test the connection and refresh its tool list.

A model with tool-calling support can request these tools while answering. Sensitive operations or actions that write data ask for your permission first. Some cases also allow you to select a tool manually, confirm its arguments, and supply the result to AI.

### 7.4 Save the ways you like to ask

**Quick Messages** hold recurring questions such as “List the key evidence.” **Custom Instructions** hold ongoing preferences such as “Explain unfamiliar terms before using them.”

**Skills** hold a fuller method and supporting material, such as a reusable article analysis framework. Import a single `SKILL.md` or a ZIP containing a Skill folder. The model can use the instructions and resources; OrigRead does not execute scripts in the package.

Adjust reasoning effort, streaming, and reasoning display where your model supports them. **Context Budget** controls how much material fits into a question and answer. If you often attach several long articles, check for omitted content before deciding to remove attachments or adjust the budget.

---

## 8. Translation, sharing and TTS

### 8.1 Read alongside a translation

1. Open **Settings → Translation**, enable a service, and choose the target language.
2. Return to an article and tap Translate to use the defaults.
3. In translation options, choose translated text only or paragraph-by-paragraph comparison, or temporarily switch the service and language.

Google ML Kit provides on-device translation. Cloud options include Microsoft Translator, DeepL, Google Cloud, and self-hosted DeepLX / DLX-compatible services. A configured OpenAI-compatible model can also translate full articles. Conventional translation services work without AI configuration.

Long articles are translated in batches and may take more time. AI translation aims to retain paragraph order and structure for comparison. Switch back to the original whenever an expression needs a closer look.

### 8.2 Share to notes or another app

Tap **Share** in the reader. On first use, choose the title, article body, and any translation or summary currently open in the reader. The original URL is included.

Later, a short tap reuses your choices; **long-press Share** to change them. Open a translation or summary first if you want it included. Content generated previously but not currently open is not attached.

OrigRead tries to preserve formatting, but the receiving app's rich-text support affects the result. Images remain external links rather than being copied as image files.

### 8.3 Listen to an article

Tap TTS to start or stop reading aloud. When translated text is displayed, it is read preferentially. Summaries have their own reading action. Change voices or the speech engine in Android's text-to-speech settings.

---

## 9. When adding a source fails

### 9.1 Discovery has not finished

Check the current stage. Feeds and public APIs are usually quicker; website parsing, RSSHub requests, or dynamic page loading can take longer. Discovery may continue after one method fails, so let the current attempt finish before retrying.

If results miss the content you want, try the specific section's list URL. It is often more useful than the website home page.

### 9.2 Dynamic discovery is slow or warns about unreliable results

A dynamic page must load and run scripts before OrigRead can look for articles, so it takes longer than reading a feed directly.

**A page loading successfully does not guarantee a usable article list.** Some dynamic candidates let you try adding the source after a warning even when no reliable list was found. This does not guarantee future refreshes. Check the article count, titles, and links first; prefer a stable feed, RSSHub route, or public API when available.

### 9.3 The browser opens the site but OrigRead cannot read it

Your browser may already be signed in. The page may also require a CAPTCHA, interaction, or paid access. OrigRead cannot bypass those conditions. Look for a feed, working RSSHub route, or public API, and use the original page to read content that requires it.

### 9.4 An RSSHub route matches but returns nothing

A match means a route was found; the instance must still return the articles. Timeouts, connection failures, and invalid responses can all prevent a subscription.

Test the current instance under **Settings → RSSHub**, switch or enable another instance if needed, and retry. A single connection failure does not require deleting route data.

### 9.5 Report a parsing problem

If you still cannot subscribe, [open an issue](https://github.com/ZGMFX01A/OrigRead/issues) with the URL, app version, section you want to follow, and discovery stage or error message.

---

## 10. When to use Website Rule / JSON API Rule

If automatic discovery already gives you the right articles, you do not need a custom rule. Use one when you want a specific section, need to exclude unrelated items, or want to improve full-text extraction.

### 10.1 Choose the rule type

A **Website Rule** finds article lists in a web page and can also specify the article body area. A **JSON / API Rule** reads public interfaces or article data embedded in a page.

Manage, import, export, and test rules under **Settings → Website Rules** or **JSON Rules**. For manual editing, open the guide in the rule manager for field explanations and examples.

### 10.2 Ask AI to help create a rule

1. Open the AI generation action in the relevant rule manager.
2. Enter a section list page or public API URL and choose a configured AI service and model.
3. Wait for generation and the trial parse, then inspect article counts, sample titles, links, and scores.
4. Save when the result contains the articles you actually want.

If normal fetching fails because the page needs scripts, Website Rule generation may offer **Retry with browser rendering**.

### 10.3 Check whether the rule works

Confirm that the articles belong to your chosen section and that their titles and links are correct. A high count or score cannot replace that check. After saving, refresh the source to check that it continues to retrieve content.

Finding article links and extracting full text are separate jobs. If the list works but an article is incomplete, keep using the list rule and adjust the body rule or read the original. Retest and update rules after a site redesign.

---

## 11. Filter unwanted articles

To keep following a source while skipping certain titles, open **Settings → Article Filters** and add a keyword or regular-expression rule. Choose a global or source-specific scope where the rule page offers it.

Check that a keyword is not too broad. A particular column name is usually more precise than a common word. Regular expressions must pass the expression check before saving. Rules can be enabled or disabled individually, imported, and exported.

Filters apply to new articles entering the timeline; **they do not delete saved historical articles**. If a rule excludes too much, disable or edit it and watch subsequent updates.

---

## 12. Backup, edition sync and accounts

### 12.1 Choose what you want to move

| What you want to do | Use |
| --- | --- |
| Move standard subscriptions and groups to another reader | OPML |
| Keep your subscriptions, rules, and preferences on a new device | Full configuration backup |
| Transfer data to the other installed OrigRead edition on the same device | Edition sync |
| Synchronize content supported by a remote account across devices | That account's sync service |

**Configuration backups exclude article bodies, read and starred states, and summary and translation caches.** They carry subscriptions, groups, parsing rules, filters, RSSHub, translation, AI, search, tool settings, and related configuration.

### 12.2 Export and restore configuration

1. Open **Settings → Backup & Restore**, choose **Export full configuration**, and save the file.
2. On the target device, choose **Restore configuration** on the same settings page and open the file.
3. Review the subscription count and other details. Enter the backup password if it contains encrypted keys, then confirm.

Subscriptions with the same URL are merged; missing ones are added. Other subscriptions and article history on the target device remain in place. Some appearance settings may require restarting the app.

### 12.3 Include saved keys

Keys are excluded by default. To bring saved AI, translation, or MCP credentials, enable the option to include saved keys before exporting and set a backup password of at least 6 characters.

You need the same password when restoring. OrigRead does not save it, so keep it somewhere you can retrieve it.

### 12.4 Sync between editions on one device

Both editions have the same features, so normal updates do not require changing apps. If you do want to move between OrigRead and OrigRead X, install both, then choose **Sync to OrigRead** or **Sync to OrigRead X** in **Backup & Restore**. Review and confirm the transfer in the receiving app.

Edition sync can transfer shared settings and the current account's subscriptions, articles, read, starred, and read-later states. Supported saved keys can also move with it. Its scope differs from a configuration file export; edition-specific data found only in the receiving app is retained.

### 12.5 Choose a local or remote account

Choose **Local** for OrigRead's RSSHub, website parsing, JSON/API sources, and local reading features.

If you already use FreshRSS, a Google Reader-compatible service, or a Fever-compatible service, add the matching account. Sync coverage depends on that remote service. Do not assume its protocol can fully support OrigRead's additional Website, JSON/API, or RSSHub source types.

---

## 13. Software updates

Open **Settings → Software Update** to check manually or choose whether to check on startup. The app displays release notes and downloads the APK matching the edition you have installed.

Keep updating your existing package. OrigRead and OrigRead X have the same features with slightly different defaults. Both remain available so you can keep your current app and settings without migrating.

On the first APK installation from the app, Android may ask you to enable **Install unknown apps**. Follow the system prompt, then continue installing.

---

## 14. FAQ

| Problem | What to try first |
| --- | --- |
| **The AI button is unavailable** | In Settings → AI Reading, enable a service, select a model, and pass the connection test |
| **The AI button summarizes when I want chat** | Enable AI conversation assistant and turn Reader summary off |
| **A citation lands somewhere unexpected** | Wait for full text; a rewritten article may leave only the source available. See [Citation](#6-citation-the-reference-can-take-you-back-to-the-evidence) |
| **Translation has no available service** | Enable a service and check the target language in Settings → Translation; AI translation also needs a working model |
| **Full-text extraction is incomplete** | Retry extraction or open the original; see [parsing rules](#10-when-to-use-website-rule--json-api-rule) for adjustments |
| **The site returns 403 / 418** | Check the address and connection, then retry later; the site may limit request frequency, region, or automated access |
| **Tablet and phone layouts differ** | Features are shared; larger screens can keep more of the lists, article, and AI visible together |

If you need more help, [report an issue](https://github.com/ZGMFX01A/OrigRead/issues) with the app version, steps, and error message. The project currently does not accept pull requests; please use issues for suggestions and documentation corrections too.

---

## 15. Other platforms

[OrigRead Desktop](https://github.com/ZGMFX01A/OrigRead-Desktop) supports Windows, macOS, and Linux.

[Repository](https://github.com/ZGMFX01A/OrigRead) · [Download updates](https://github.com/ZGMFX01A/OrigRead/releases/latest) · [Report an issue](https://github.com/ZGMFX01A/OrigRead/issues)
