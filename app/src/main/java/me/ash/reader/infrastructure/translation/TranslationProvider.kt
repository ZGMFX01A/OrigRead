package me.ash.reader.infrastructure.translation

/** 所有翻译实现遵循同一批量接口，由上层统一负责分段、缓存和错误展示。 */
interface TranslationProvider {
    val type: TranslationProviderType
    val maxBatchItems: Int
        get() = 50
    val maxBatchCharacters: Int
        get() = 30_000
    val maxSegmentCharacters: Int
        get() = 4_000

    suspend fun translate(
        texts: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        config: TranslationRuntimeConfig,
    ): TranslationBatchResult
}

/** 将长段落拆成连续片段，并在翻译完成后按原始文本索引重新合并。 */
object TranslationSegmenter {
    data class Segment(val sourceIndex: Int, val text: String)

    fun splitAll(texts: List<String>, maxCharacters: Int): List<Segment> =
        texts.flatMapIndexed { index, text ->
            split(text, maxCharacters).map { Segment(index, it) }
        }

    fun merge(segments: List<Segment>, translated: List<String>, sourceCount: Int): List<String> {
        require(segments.size == translated.size)
        val output = MutableList(sourceCount) { StringBuilder() }
        segments.zip(translated).forEach { (segment, value) ->
            output[segment.sourceIndex].append(value)
        }
        return output.map(StringBuilder::toString)
    }

    fun split(text: String, maxCharacters: Int): List<String> {
        require(maxCharacters > 0)
        if (text.length <= maxCharacters) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(start + maxCharacters, text.length)
            if (hardEnd == text.length) {
                result += text.substring(start)
                break
            }
            val searchStart = start + maxCharacters / 2
            var end = hardEnd
            for (index in hardEnd - 1 downTo searchStart) {
                if (text[index] in BREAK_CHARACTERS) {
                    end = index + 1
                    break
                }
            }
            result += text.substring(start, end)
            start = end
        }
        return result
    }

    private val BREAK_CHARACTERS =
        setOf('\n', ' ', '。', '！', '？', '；', '.', '!', '?', ';', ',')
}

