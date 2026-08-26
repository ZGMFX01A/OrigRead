package me.ash.reader.infrastructure.net

import java.nio.charset.Charset

/** HTTP 文本类型，用于在响应头未声明 charset 时选择协议内的编码声明。 */
enum class HttpTextKind {
    HTML,
    XML,
    AUTO,
}

/**
 * 解码网页/XML 响应正文。
 *
 * 优先使用 HTTP Content-Type 中的显式 charset；缺失时再识别 BOM、XML declaration
 * 或 HTML meta charset，最后才回退 UTF-8。GBK/GB2312 统一用 GB18030 超集解码。
 */
object HttpTextDecoder {
    private val charsetRegex = Regex("charset\\s*=\\s*[\\\"']?([^;\\\"'\\s>]+)", RegexOption.IGNORE_CASE)
    private val xmlEncodingRegex =
        Regex("<\\?xml[^>]*encoding\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
    private val htmlMetaCharsetRegex =
        Regex("<meta\\b[^>]*charset\\s*=\\s*[\\\"']?\\s*([A-Za-z0-9._:-]+)", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray, contentType: String?, kind: HttpTextKind = HttpTextKind.AUTO): String {
        val bom = detectBom(bytes)
        val charset =
            charsetFromContentType(contentType)
                ?: bom?.charset
                ?: charsetFromPayload(bytes, kind)
                ?: Charsets.UTF_8
        val offset = if (bom?.charset == charset) bom.byteCount else 0
        return String(bytes, offset, bytes.size - offset, charset)
    }

    private fun charsetFromPayload(bytes: ByteArray, kind: HttpTextKind): Charset? {
        val prefix = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1)
        return when (kind) {
            HttpTextKind.XML -> charsetFromXml(prefix)
            HttpTextKind.HTML -> charsetFromHtml(prefix)
            HttpTextKind.AUTO -> charsetFromXml(prefix) ?: charsetFromHtml(prefix)
        }
    }

    private fun charsetFromContentType(contentType: String?): Charset? =
        contentType
            ?.let(charsetRegex::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::safeCharset)

    private fun charsetFromXml(prefix: String): Charset? =
        xmlEncodingRegex.find(prefix)?.groupValues?.getOrNull(1)?.let(::safeCharset)

    private fun charsetFromHtml(prefix: String): Charset? =
        htmlMetaCharsetRegex.find(prefix)?.groupValues?.getOrNull(1)?.let(::safeCharset)

    private fun safeCharset(label: String): Charset? =
        runCatching {
            when (label.trim().lowercase()) {
                "gbk", "gb2312", "gb_2312", "gb18030" -> Charset.forName("GB18030")
                else -> Charset.forName(label.trim())
            }
        }.getOrNull()

    private fun detectBom(bytes: ByteArray): Bom? =
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                Bom(Charsets.UTF_8, 3)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Bom(Charsets.UTF_16BE, 2)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Bom(Charsets.UTF_16LE, 2)
            else -> null
        }

    private data class Bom(val charset: Charset, val byteCount: Int)

    private const val SNIFF_BYTES = 8 * 1024
}
