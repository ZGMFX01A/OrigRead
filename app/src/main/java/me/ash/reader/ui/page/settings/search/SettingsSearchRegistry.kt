package me.ash.reader.ui.page.settings.search

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Segment
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ReportGmailerrorred
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Title
import me.ash.reader.R
import me.ash.reader.ui.ext.isGitHub
import me.ash.reader.ui.page.nav3.key.Route

/**
 * 设置项搜索注册表与检索引擎。
 * 集中管理应用内各二级、三级设置项的元数据，并提供高效的内存模糊检索能力。
 */
object SettingsSearchRegistry {

    /**
     * 全局设置项静态定义列表。
     */
    val allItems: List<SettingsSearchItem> = buildList {
        // ==================== 1. AI 设置 ====================
        add(
            SettingsSearchItem(
                id = "ai_settings",
                titleRes = R.string.ai_settings,
                descRes = R.string.ai_settings_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.ai_settings),
                route = Route.AiSettings,
                icon = Icons.Outlined.AutoAwesome,
                keywords = listOf("AI", "LLM", "大模型", "ChatGPT", "Claude", "Gemini", "DeepSeek", "API Key", "模型"),
            )
        )

        // ==================== 2. 翻译设置 ====================
        add(
            SettingsSearchItem(
                id = "translation_settings",
                titleRes = R.string.translation_settings,
                descRes = R.string.translation_settings_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.translation_settings),
                route = Route.TranslationSettings,
                icon = Icons.Outlined.Translate,
                keywords = listOf("翻译", "Translate", "双语", "DeepL", "Google"),
            )
        )
        add(
            SettingsSearchItem(
                id = "translation_target_language",
                titleRes = R.string.translation_target_language,
                descRes = R.string.translation_target_language_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.translation_settings),
                route = Route.TranslationSettings,
                icon = Icons.Outlined.Translate,
                keywords = listOf("目标语言", "翻译语言", "自动翻译"),
            )
        )

        // ==================== 3. 文章过滤 ====================
        add(
            SettingsSearchItem(
                id = "article_filter_settings",
                titleRes = R.string.article_filter_settings,
                descRes = R.string.article_filter_settings_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.article_filter_settings),
                route = Route.ArticleFilters,
                icon = Icons.Outlined.FilterAlt,
                keywords = listOf("过滤", "屏蔽", "关键词", "黑名单", "正则", "Filter", "规则"),
            )
        )

        // ==================== 4. 规则引擎 ====================
        add(
            SettingsSearchItem(
                id = "json_rules",
                titleRes = R.string.json_rules,
                descRes = R.string.json_rules_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.json_rules),
                route = Route.JsonRules,
                icon = Icons.Outlined.DataObject,
                keywords = listOf("JSON", "规则", "抓取", "解析", "数据源"),
            )
        )
        add(
            SettingsSearchItem(
                id = "website_rules",
                titleRes = R.string.website_rules,
                descRes = R.string.website_rules_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.website_rules),
                route = Route.WebsiteRules,
                icon = Icons.Outlined.Rule,
                keywords = listOf("网站规则", "全文提取", "正文抽取", "Web rule", "Readability", "净化"),
            )
        )

        // ==================== 5. RSSHub 设置 ====================
        add(
            SettingsSearchItem(
                id = "rsshub_settings",
                titleRes = R.string.rsshub_settings,
                descRes = R.string.rsshub_settings_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.rsshub_settings),
                route = Route.RssHubSettings,
                icon = Icons.Outlined.RssFeed,
                keywords = listOf("RSSHub", "实例", "域名", "AccessKey", "路由", "订阅源"),
            )
        )

        // ==================== 6. 账户设置 ====================
        add(
            SettingsSearchItem(
                id = "accounts",
                titleRes = R.string.accounts,
                descRes = R.string.accounts_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.accounts),
                route = Route.Accounts,
                icon = Icons.Outlined.AccountCircle,
                keywords = listOf("账号", "同步", "Google Reader", "FreshRSS", "Miniflux", "Tiny Tiny RSS", "Nextcloud", "Fever", "Inoreader"),
            )
        )
        add(
            SettingsSearchItem(
                id = "add_accounts",
                titleRes = R.string.add_accounts,
                breadcrumbsRes = listOf(R.string.settings, R.string.accounts),
                route = Route.AddAccounts,
                icon = Icons.Outlined.AccountCircle,
                keywords = listOf("添加账号", "登录账号", "新建服务"),
            )
        )

        // ==================== 7. 配置备份与恢复 ====================
        add(
            SettingsSearchItem(
                id = "configuration_backup",
                titleRes = R.string.configuration_backup_title,
                descRes = R.string.configuration_backup_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.configuration_backup_title),
                route = Route.ConfigurationBackup,
                icon = Icons.Outlined.SettingsBackupRestore,
                keywords = listOf("备份", "恢复", "导出", "导入", "OPML", "WebDAV", "同步配置", "云备份"),
            )
        )

        // ==================== 8. 软件更新（仅 GitHub 渠道可用） ====================
        add(
            SettingsSearchItem(
                id = "software_update",
                titleRes = R.string.software_update,
                descRes = R.string.software_update_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.software_update),
                route = Route.SoftwareUpdate,
                icon = Icons.Outlined.SystemUpdate,
                keywords = listOf("更新", "版本", "升级", "Update", "Release"),
                isAvailable = { isGitHub },
            )
        )
        add(
            SettingsSearchItem(
                id = "auto_check_updates",
                titleRes = R.string.auto_check_updates,
                descRes = R.string.auto_check_updates_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.software_update),
                route = Route.SoftwareUpdate,
                icon = Icons.Outlined.SystemUpdate,
                keywords = listOf("自动检查更新", "后台检查"),
                isAvailable = { isGitHub },
            )
        )
        add(
            SettingsSearchItem(
                id = "check_updates_now",
                titleRes = R.string.check_updates_now,
                breadcrumbsRes = listOf(R.string.settings, R.string.software_update),
                route = Route.SoftwareUpdate,
                icon = Icons.Outlined.SystemUpdate,
                keywords = listOf("立即检查更新", "新版本"),
                isAvailable = { isGitHub },
            )
        )

        // ==================== 9. 色彩与样式 ====================
        add(
            SettingsSearchItem(
                id = "dark_theme",
                titleRes = R.string.dark_theme,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.dark_theme),
                route = Route.DarkTheme,
                icon = Icons.Outlined.Palette,
                keywords = listOf("深色模式", "夜间模式", "暗黑模式", "深色", "Dark mode"),
            )
        )
        add(
            SettingsSearchItem(
                id = "amoled_dark_theme",
                titleRes = R.string.amoled_dark_theme,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.dark_theme),
                route = Route.DarkTheme,
                icon = Icons.Outlined.Palette,
                keywords = listOf("纯黑", "AMOLED", "黑色背景", "省电"),
            )
        )
        add(
            SettingsSearchItem(
                id = "basic_fonts",
                titleRes = R.string.basic_fonts,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style),
                route = Route.ColorAndStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("界面字体", "字体", "基础字体", "外部字体", "TTF", "OTF"),
            )
        )
        add(
            SettingsSearchItem(
                id = "wallpaper_colors",
                titleRes = R.string.wallpaper_colors,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style),
                route = Route.ColorAndStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("壁纸颜色", "动态取色", "Material You", "壁纸配色"),
            )
        )
        add(
            SettingsSearchItem(
                id = "basic_colors",
                titleRes = R.string.basic_colors,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style),
                route = Route.ColorAndStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("强调色", "主题色", "预设颜色", "色调"),
            )
        )
        add(
            SettingsSearchItem(
                id = "feeds_page_style",
                titleRes = R.string.feeds_page,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.feeds_page),
                route = Route.FeedsPageStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("分组页样式", "订阅源列表", "角标", "未读计数", "顶栏高度"),
            )
        )
        add(
            SettingsSearchItem(
                id = "flow_page_style",
                titleRes = R.string.flow_page,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.flow_page),
                route = Route.FlowPageStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("文章列表样式", "卡片样式", "封面图", "文章流", "摘要行数", "时间格式"),
            )
        )

        // ==================== 10. 阅读页排版与正文样式 ====================
        add(
            SettingsSearchItem(
                id = "reading_page_style",
                titleRes = R.string.reading_page,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page),
                route = Route.ReadingPageStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("阅读页样式", "阅读主题", "纸张背景", "排版", "正文设置"),
            )
        )
        add(
            SettingsSearchItem(
                id = "content_renderer",
                titleRes = R.string.content_renderer,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page),
                route = Route.ReadingPageStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("渲染引擎", "WebView", "原生组件", "Renderer"),
            )
        )
        add(
            SettingsSearchItem(
                id = "bold_characters",
                titleRes = R.string.bold_characters,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page),
                route = Route.ReadingPageStyle,
                icon = Icons.Rounded.Title,
                keywords = listOf("加粗字", "仿生阅读", "Bionic reading", "重点加粗"),
            )
        )
        add(
            SettingsSearchItem(
                id = "reading_fonts",
                titleRes = R.string.reading_fonts,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page),
                route = Route.ReadingPageStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("阅读字体", "正文字体", "导入字体"),
            )
        )
        add(
            SettingsSearchItem(
                id = "auto_hide_toolbars",
                titleRes = R.string.auto_hide_toolbars,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page),
                route = Route.ReadingPageStyle,
                icon = Icons.Outlined.Palette,
                keywords = listOf("自动隐藏工具栏", "全屏阅读", "隐藏顶栏底栏"),
            )
        )
        add(
            SettingsSearchItem(
                id = "reading_text_settings",
                titleRes = R.string.text,
                descRes = R.string.text_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.text),
                route = Route.ReadingPageText,
                icon = Icons.AutoMirrored.Outlined.Segment,
                keywords = listOf("正文排版", "文本设置", "段落"),
            )
        )
        add(
            SettingsSearchItem(
                id = "font_size",
                titleRes = R.string.font_size,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.text),
                route = Route.ReadingPageText,
                icon = Icons.AutoMirrored.Outlined.Segment,
                keywords = listOf("字号", "字体大小", "字号调节", "放大字号"),
            )
        )
        add(
            SettingsSearchItem(
                id = "line_height",
                titleRes = R.string.line_height_multiple,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.text),
                route = Route.ReadingPageText,
                icon = Icons.AutoMirrored.Outlined.Segment,
                keywords = listOf("行高", "行距", "行间距", "多倍行距"),
            )
        )
        add(
            SettingsSearchItem(
                id = "reading_text_bold",
                titleRes = R.string.bold,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.text),
                route = Route.ReadingPageText,
                icon = Icons.AutoMirrored.Outlined.Segment,
                keywords = listOf("加粗", "正文加粗", "粗体"),
            )
        )
        add(
            SettingsSearchItem(
                id = "reading_text_alignment",
                titleRes = R.string.alignment,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.text),
                route = Route.ReadingPageText,
                icon = Icons.AutoMirrored.Outlined.Segment,
                keywords = listOf("对齐方式", "两端对齐", "居左对齐", "居中"),
            )
        )
        add(
            SettingsSearchItem(
                id = "letter_spacing",
                titleRes = R.string.letter_spacing,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.text),
                route = Route.ReadingPageText,
                icon = Icons.AutoMirrored.Outlined.Segment,
                keywords = listOf("字距", "字间距"),
            )
        )
        add(
            SettingsSearchItem(
                id = "horizontal_padding",
                titleRes = R.string.horizontal_padding,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.text),
                route = Route.ReadingPageText,
                icon = Icons.AutoMirrored.Outlined.Segment,
                keywords = listOf("页面边距", "水平边距", "左右边距", "内边距"),
            )
        )
        add(
            SettingsSearchItem(
                id = "reading_title_settings",
                titleRes = R.string.title,
                descRes = R.string.title_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.title),
                route = Route.ReadingPageTitle,
                icon = Icons.Rounded.Title,
                keywords = listOf("标题设置", "标题排版", "标题对齐", "标题加粗"),
            )
        )
        add(
            SettingsSearchItem(
                id = "reading_images",
                titleRes = R.string.images,
                descRes = R.string.images_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.images),
                route = Route.ReadingPageImage,
                icon = Icons.Outlined.Image,
                keywords = listOf("图片", "正文图片", "圆角", "最大化图片", "图片宽度"),
            )
        )
        add(
            SettingsSearchItem(
                id = "reading_videos",
                titleRes = R.string.videos,
                descRes = R.string.videos_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.color_and_style, R.string.reading_page, R.string.videos),
                route = Route.ReadingPageVideo,
                icon = Icons.Outlined.Movie,
                keywords = listOf("视频", "正文视频", "视频播放"),
            )
        )

        // ==================== 11. 交互与手势 ====================
        add(
            SettingsSearchItem(
                id = "initial_page",
                titleRes = R.string.initial_page,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.on_start),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("启动页面", "首屏", "启动时默认页面", "主界面"),
            )
        )
        add(
            SettingsSearchItem(
                id = "initial_filter",
                titleRes = R.string.initial_filter,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.on_start),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("默认筛选", "初始过滤", "未读优先", "全部文章"),
            )
        )
        add(
            SettingsSearchItem(
                id = "hide_empty_groups",
                titleRes = R.string.hide_empty_groups,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.feeds_page),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("隐藏空分组", "空分类", "已读折叠"),
            )
        )
        add(
            SettingsSearchItem(
                id = "article_list_style",
                titleRes = R.string.article_list_style,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.article_list),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("文章列表样式", "分隔线列表", "按日分组卡片", "依日期分組", "cards grouped by day", "独立卡片", "分隔線", "獨立卡片", "list with dividers", "individual cards",
                    "分段卡片", "分割线", "内缩分割线", "卡片样式", "呼吸缝", "Expressive", "card style", "divider"),
            )
        )
        add(
            SettingsSearchItem(
                id = "swipe_to_start",
                titleRes = R.string.swipe_to_start,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.article_list),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("左滑", "滑动手势", "滑动标记", "滑动已读", "左划"),
            )
        )
        add(
            SettingsSearchItem(
                id = "swipe_to_end",
                titleRes = R.string.swipe_to_end,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.article_list),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("右滑", "滑动手势", "加星标", "收藏", "稍后阅读", "右划"),
            )
        )
        add(
            SettingsSearchItem(
                id = "sort_unread_articles",
                titleRes = R.string.sort_unread_articles,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.article_list),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("未读排序", "文章排序", "时间排序", "正序", "倒序"),
            )
        )
        add(
            SettingsSearchItem(
                id = "mark_as_read_on_scroll",
                titleRes = R.string.mark_as_read_on_scroll,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.article_list),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("滚动标记为已读", "滑动已读", "划过已读", "自动已读"),
            )
        )
        add(
            SettingsSearchItem(
                id = "pull_from_bottom",
                titleRes = R.string.pull_from_bottom,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.article_list),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("上拉加载", "到底切换订阅源", "底部上拉"),
            )
        )
        add(
            SettingsSearchItem(
                id = "pull_to_switch_article",
                titleRes = R.string.pull_to_switch_article,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.reading_page),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("上拉切换文章", "下一篇", "翻篇", "底部切换"),
            )
        )
        add(
            SettingsSearchItem(
                id = "initial_open_app",
                titleRes = R.string.initial_open_app,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.external_links),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("打开链接", "内置浏览器", "默认浏览器", "外部打开", "Chrome"),
            )
        )
        add(
            SettingsSearchItem(
                id = "open_link_specific_browser",
                titleRes = R.string.open_link_specific_browser,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.external_links),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("特定浏览器", "指定浏览器", "第三方浏览器"),
            )
        )
        add(
            SettingsSearchItem(
                id = "shared_content",
                titleRes = R.string.list_shared_content,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.share),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("分享内容", "分享格式", "带标题", "带链接", "列表分享"),
            )
        )
        add(
            SettingsSearchItem(
                id = "reading_share",
                titleRes = R.string.reading_share_config_title,
                descRes = R.string.reading_share_config_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.interaction, R.string.share),
                route = Route.Interaction,
                icon = Icons.Outlined.TouchApp,
                keywords = listOf("分享内容", "分享目标", "正文", "译文", "摘要", "Obsidian", "SiYuan", "Logseq", "Notion"),
            )
        )

        // ==================== 12. 多语言 ====================
        add(
            SettingsSearchItem(
                id = "languages",
                titleRes = R.string.languages,
                descRes = null,
                breadcrumbsRes = listOf(R.string.settings, R.string.languages),
                route = Route.Languages,
                icon = Icons.Outlined.Language,
                keywords = listOf("语言", "简体中文", "English", "繁体中文", "Language", "系统语言"),
            )
        )

        // ==================== 13. 故障排除 ====================
        add(
            SettingsSearchItem(
                id = "troubleshooting",
                titleRes = R.string.troubleshooting,
                descRes = R.string.troubleshooting_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.troubleshooting),
                route = Route.Troubleshooting,
                icon = Icons.Outlined.ReportGmailerrorred,
                keywords = listOf("故障排除", "错误日志", "同步日志", "日志导出", "清空日志"),
            )
        )

        // ==================== 14. 提示与支持 ====================
        add(
            SettingsSearchItem(
                id = "tips_and_support",
                titleRes = R.string.tips_and_support,
                descRes = R.string.tips_and_support_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.tips_and_support),
                route = Route.TipsAndSupport,
                icon = Icons.Outlined.TipsAndUpdates,
                keywords = listOf("关于", "原读", "版本号", "帮助", "支持", "赞助", "GitHub"),
            )
        )
        add(
            SettingsSearchItem(
                id = "user_guide",
                titleRes = R.string.user_guide_title,
                descRes = R.string.user_guide_desc,
                breadcrumbsRes = listOf(R.string.settings, R.string.tips_and_support),
                route = Route.TipsAndSupport,
                icon = Icons.Outlined.Description,
                keywords = listOf("操作手册", "用户指南", "使用说明", "快捷键"),
            )
        )
    }

    /**
     * 根据关键词在所有设置项中检索，支持标题、描述、面包屑分类与扩展关键词匹配。
     *
     * @param context Android 上下文，用于实时解析当前语言的字符串资源
     * @param query 用户输入的搜索词
     * @return 匹配并按相关度降序排列的搜索结果列表
     */
    fun search(context: Context, query: String): List<SettingsSearchResult> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return emptyList()

        return allItems
            .filter { it.isAvailable() }
            .mapNotNull { item ->
                val title = context.getString(item.titleRes)
                val desc = item.descRes?.let { context.getString(it) }
                val breadcrumbList = item.breadcrumbsRes.map { context.getString(it) }
                val breadcrumbText = breadcrumbList.joinToString(" > ")

                val score = calculateScore(
                    query = trimmedQuery,
                    title = title,
                    desc = desc,
                    breadcrumbList = breadcrumbList,
                    keywords = item.keywords,
                )

                if (score > 0) {
                    SettingsSearchResult(
                        item = item,
                        title = title,
                        desc = desc,
                        breadcrumbText = breadcrumbText,
                        score = score,
                    )
                } else {
                    null
                }
            }
            .sortedWith(
                compareByDescending<SettingsSearchResult> { it.score }
                    .thenBy { it.title.length }
            )
    }

    /**
     * 计算单条设置项与搜索关键词的相关度得分。
     */
    private fun calculateScore(
        query: String,
        title: String,
        desc: String?,
        breadcrumbList: List<String>,
        keywords: List<String>,
    ): Int {
        // 标题完全匹配（最高权重）
        if (title.equals(query, ignoreCase = true)) {
            return 100
        }
        // 标题以关键词开头
        if (title.startsWith(query, ignoreCase = true)) {
            return 80
        }
        // 标题包含关键词
        if (title.contains(query, ignoreCase = true)) {
            return 60
        }
        // 自定义同义词/关键词包含匹配
        if (keywords.any { it.contains(query, ignoreCase = true) }) {
            return 45
        }
        // 描述信息包含关键词
        if (desc?.contains(query, ignoreCase = true) == true) {
            return 30
        }
        // 面包屑路径层级包含关键词
        if (breadcrumbList.any { it.contains(query, ignoreCase = true) }) {
            return 15
        }

        return 0
    }
}
