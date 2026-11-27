package me.ash.reader.infrastructure.security

/** 外部规则使用保守的正则子集；先检查结构，再交给 Java 正则编译器检查语法。 */
object SafeRegexGuard {
    const val MAX_PATTERN_LENGTH = 512
    private const val MAX_REPEAT = 1000
    private data class Group(var repeated: Boolean = false, var alternative: Boolean = false)

    fun compile(pattern: String, options: Set<RegexOption> = emptySet()): Regex {
        validate(pattern)
        return Regex(pattern, options)
    }

    fun validate(pattern: String) {
        require(pattern.length <= MAX_PATTERN_LENGTH) { "正则表达式超过 512 字符限制" }
        val groups = mutableListOf(Group())
        var atom: Group? = null
        var lastWasQuantifier = false
        var index = 0
        while (index < pattern.length) {
            when (val c = pattern[index++]) {
                '\\' -> {
                    require(index < pattern.length) { "正则转义不完整" }
                    val escaped = pattern[index++]
                    require(!escaped.isDigit() && escaped !in "kgQ") { "不支持反向引用或引用块" }
                    atom = Group()
                    lastWasQuantifier = false
                }
                '[' -> {
                    if (index < pattern.length && pattern[index] == '^') index++
                    if (index < pattern.length && pattern[index] == ']') index++
                    var closed = false
                    while (index < pattern.length) {
                        val token = pattern[index++]
                        if (token == '\\' && index < pattern.length) index++
                        else if (token == ']') { closed = true; break }
                        else require(token != '[') { "不支持嵌套字符类" }
                    }
                    require(closed) { "正则字符类未闭合" }
                    atom = Group()
                    lastWasQuantifier = false
                }
                '(' -> {
                    if (index < pattern.length && pattern[index] == '?') {
                        // 只允许非捕获组和标准的大小写等 flags，拒绝可隐藏重复结构的 lookaround。
                        val end = pattern.indexOfAny(charArrayOf(':', ')'), index + 1)
                        require(end >= 0 && pattern.substring(index + 1, end).all { it in "idmsuxU-" }) {
                            "不支持此正则分组结构"
                        }
                        index = end
                        if (pattern[index] == ')') {
                            index++
                            atom = null
                            lastWasQuantifier = false
                            continue
                        }
                        index++
                    }
                    groups.add(Group())
                    atom = null
                    lastWasQuantifier = false
                }
                ')' -> {
                    require(groups.size > 1) { "正则分组未配对" }
                    atom = groups.removeAt(groups.lastIndex)
                    groups.last().repeated = groups.last().repeated || atom.repeated
                    groups.last().alternative = groups.last().alternative || atom.alternative
                    lastWasQuantifier = false
                }
                '|' -> {
                    groups.last().alternative = true
                    atom = null
                    lastWasQuantifier = false
                }
                '*', '+', '?', '{' -> {
                    // 惰性和占有修饰符必须紧跟前一个量词，不能把 ^+ / (?i)+ 误判为修饰符。
                    if (c in "?+" && lastWasQuantifier) {
                        lastWasQuantifier = false
                        continue
                    }
                    var repeats = c != '?'
                    if (c == '{') {
                        val end = pattern.indexOf('}', index)
                        require(end >= 0) { "正则量词未闭合" }
                        val bounds = pattern.substring(index, end).split(',')
                        require(bounds.size in 1..2 && bounds[0].isNotEmpty()) { "正则量词无效" }
                        val counts = bounds.filter(String::isNotEmpty).map {
                            it.toIntOrNull()?.also { n -> require(n <= MAX_REPEAT) { "正则重复次数过大" } }
                                ?: throw IllegalArgumentException("正则量词无效")
                        }
                        repeats = (bounds.size == 2 && bounds[1].isEmpty()) || counts.any { it > 1 }
                        index = end + 1
                    }
                    val previous = atom ?: throw IllegalArgumentException("正则量词缺少目标")
                    require(!repeats || (!previous.repeated && !previous.alternative)) {
                        "拒绝嵌套量词或重复交替分组，可能导致回溯失控"
                    }
                    if (repeats) groups.last().repeated = true
                    atom = null
                    lastWasQuantifier = true
                }
                '^', '$' -> {
                    // 锚点不能被量化，避免 (?i)^+ 等无效结构逃过量词目标检查。
                    atom = null
                    lastWasQuantifier = false
                }
                else -> {
                    atom = Group()
                    lastWasQuantifier = false
                }
            }
        }
        require(groups.size == 1) { "正则分组未闭合" }
        // 语法错误（含不存在的引用）必须在保存或匹配前明确失败。
        Regex(pattern)
    }
}
