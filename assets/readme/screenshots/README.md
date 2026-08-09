# OrigRead README 截图清单

这里存放 README 使用的正式产品截图。截图按 UI 语言分成两套，避免英文用户看到中文界面、中文用户看到英文界面。

目录结构固定为：

```text
assets/readme/screenshots/
├─ zh-CN/
│  ├─ overview.png
│  ├─ source-discovery.png
│  ├─ reading.png
│  ├─ ai-summary.png
│  ├─ translation.png
│  ├─ rules.png
│  └─ settings-backup.png
└─ en-US/
   ├─ overview.png
   ├─ source-discovery.png
   ├─ reading.png
   ├─ ai-summary.png
   ├─ translation.png
   ├─ rules.png
   └─ settings-backup.png
```

`README-zh-CN.md` 只引用 `zh-CN/`，`README.md` 只引用 `en-US/`。后续把截图放到对应文件名，并取消 README 中相应 `<img>` 行的 HTML 注释即可。

## 推荐截图

1. `overview.png`
   - 用途：README 顶部总览横图，可选。
   - 建议：2～4 张手机截图拼成一张横向功能总览。
   - 重点：来源发现、阅读页、AI 摘要、规则/设置至少出现三个。

2. `source-discovery.png`
   - 页面：来源发现 / 添加来源结果。
   - 重点：内置 Feed 目录、分类，或者同一 URL 的 RSS / RSSHub / JSON / 网页解析候选。
   - 这是最能体现 OrigRead 与普通 RSS 阅读器区别的截图之一。

3. `reading.png`
   - 页面：文章阅读页。
   - 重点：全文正文、顶部阅读工具、底部核心操作，尽量选择图文完整且视觉效果好的文章。

4. `ai-summary.png`
   - 页面：阅读页打开 AI 摘要后的状态。
   - 重点：正文与 AI 摘要同时可见、模型名称、Markdown 摘要内容。
   - 不需要展示模型思考过程。

5. `translation.png`
   - 页面：文章翻译。
   - 重点：双语对照或译文模式；如果能同时表现传统翻译 / AI 模型选择更好。

6. `rules.png`
   - 页面：网站解析规则或 JSON/API 解析规则。
   - 重点：规则列表、AI 生成规则入口、测试/导入导出/帮助文档中的任一组即可。

7. `settings-backup.png`
   - 页面：设置 / 备份与恢复 / 软件更新。
   - 重点：完整配置备份、API Key 加密迁移、软件更新入口中的一项或两项。

## 截图建议

- 两套截图建议使用同一台设备、同一主题、同一文章/来源和同一页面状态，只切换 App 语言后重新截图，这样中英文 README 的视觉和内容能一一对应。
- 中文截图放在 `zh-CN/`，英文截图放在 `en-US/`，不要用 `*-en.png` 这类后缀混放在同一目录。
- 手机截图建议保持原始比例，不要截入系统通知、私人账号、API Key、邮箱、订阅私密信息等内容。
- README 三列布局下单图宽度约 280px，因此原图使用 1080p 左右即可，没有必要上传超大无损图。
- PNG / WebP 都可以；当前 README 预留的是 PNG 文件名。
- 不建议继续使用 `fastlane/metadata/android/*/images/phoneScreenshots/` 中的旧 Read You 截图作为 OrigRead 1.0 主展示图，它们无法体现当前新增的来源解析、AI、翻译和配置迁移能力。

## 拍摄顺序

建议先完整拍一套中文，再把 App 切换到英文后按相同顺序重拍一套：

1. 来源发现
2. 阅读与全文
3. AI 摘要
4. 翻译
5. 网站/JSON 解析规则
6. 设置、备份与更新
7. 可选的总览拼图
