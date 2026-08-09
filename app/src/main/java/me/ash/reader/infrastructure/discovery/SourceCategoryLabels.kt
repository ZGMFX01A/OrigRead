package me.ash.reader.infrastructure.discovery

/**
 * 内置来源目录的分类本地化表。
 *
 * key 始终保持上游目录里的英文原分类，避免改变分类语义；展示层再根据当前语言选择中文标签。
 * 同时保留中英文搜索词，因此中文界面可以直接搜索“编程”“科技”“网络安全”等分类。
 */
object SourceCategoryLabels {
    private data class Label(
        val simplifiedChinese: String,
        val traditionalChinese: String,
    )

    private val labels =
        mapOf(
            "AI" to Label("人工智能", "人工智慧"),
            "Android" to Label("安卓", "Android"),
            "Android Development" to Label("Android 开发", "Android 開發"),
            "Animal & Wildlife" to Label("动物与野生生物", "動物與野生動物"),
            "Apple" to Label("Apple 生态", "Apple 生態"),
            "Architecture" to Label("建筑", "建築"),
            "Articles" to Label("文章", "文章"),
            "Beauty" to Label("美妆", "美妝"),
            "Books" to Label("图书", "書籍"),
            "Business & Economy" to Label("商业与经济", "商業與經濟"),
            "Cars" to Label("汽车", "汽車"),
            "Chess" to Label("国际象棋", "西洋棋"),
            "Cricket" to Label("板球", "板球"),
            "Cryptocurrency" to Label("加密货币", "加密貨幣"),
            "Cyber security" to Label("网络安全", "網路安全"),
            "DIY" to Label("DIY 手作", "DIY 手作"),
            "Environment" to Label("环境", "環境"),
            "Fashion" to Label("时尚", "時尚"),
            "Food" to Label("美食", "美食"),
            "Football" to Label("足球", "足球"),
            "Funny" to Label("搞笑", "搞笑"),
            "Gaming" to Label("游戏", "遊戲"),
            "History" to Label("历史", "歷史"),
            "Interior design" to Label("室内设计", "室內設計"),
            "iOS Development" to Label("iOS 开发", "iOS 開發"),
            "Memes" to Label("梗图", "迷因"),
            "Movies" to Label("电影", "電影"),
            "Music" to Label("音乐", "音樂"),
            "Nature" to Label("自然", "自然"),
            "News" to Label("新闻", "新聞"),
            "Personal finance" to Label("个人理财", "個人理財"),
            "Photography" to Label("摄影", "攝影"),
            "Product" to Label("产品", "產品"),
            "Programming" to Label("编程", "程式設計"),
            "Science" to Label("科学", "科學"),
            "Space" to Label("太空", "太空"),
            "Sports" to Label("体育", "體育"),
            "Startups" to Label("创业", "創業"),
            "Tech" to Label("科技", "科技"),
            "Television" to Label("电视", "電視"),
            "Tennis" to Label("网球", "網球"),
            "Travel" to Label("旅行", "旅行"),
            "UI - UX" to Label("UI / UX", "UI / UX"),
            "Web Development" to Label("Web 开发", "Web 開發"),
        )

    /** 根据界面语言返回分类名称；未维护的新分类自动回退为上游英文原值。 */
    fun localized(category: String, languageTag: String): String {
        val label = labels[category] ?: return category
        return when {
            languageTag.isTraditionalChinese() -> label.traditionalChinese
            languageTag.isChinese() -> label.simplifiedChinese
            else -> category
        }
    }

    /** 中文界面在分类面板里额外显示英文原分类，方便识别上游分类来源。 */
    fun secondary(category: String, languageTag: String): String? =
        if (languageTag.isChinese() && localized(category, languageTag) != category) category else null

    /** 搜索同时匹配英文、简中和繁中分类名。 */
    fun searchTerms(category: String): List<String> {
        val label = labels[category] ?: return listOf(category)
        return listOf(category, label.simplifiedChinese, label.traditionalChinese).distinct()
    }

    fun hasLocalizedLabel(category: String): Boolean = category in labels

    private fun String.isChinese(): Boolean = lowercase().startsWith("zh")

    private fun String.isTraditionalChinese(): Boolean {
        val normalized = lowercase()
        return normalized.startsWith("zh-hant") ||
            normalized.startsWith("zh-tw") ||
            normalized.startsWith("zh-hk") ||
            normalized.startsWith("zh-mo")
    }
}
