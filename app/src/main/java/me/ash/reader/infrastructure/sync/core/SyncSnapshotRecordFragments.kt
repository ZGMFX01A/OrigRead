package me.ash.reader.infrastructure.sync.core

import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

/** 原 wire 按片段编码，来源已经规范化后不再随每个字段解码或重新编码。 */
internal object SyncSnapshotRecordFragments {
    data class Input(val record: SyncSnapshotRecord, val source: String? = null)
    data class Prepared(val hash: String, val stored: String)
    private data class Member(val name: String, val value: String, val source: Boolean)
    /** 记录承诺仍使用原协议的 SHA-256 摘要算法。 */
    private const val HASH_ALGORITHM = "SHA-256"

    /** 顺序与原 canonical JSON 一致，正文、null 和完整签名对象均保持原字节语义。 */
    fun fragments(input: Input): Sequence<String> = sequence {
        yieldAll(headerFragments(input.record))
        yieldAll(valueFragments(input))
        yield("}")
    }

    /** 原始紧凑事实不变时只补完整 wire 摘要，不再次构建整条落盘正文。 */
    fun hash(input: Input): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        fragments(input).forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return digest.digest().toHexString()
    }

    /** 同一成员只规范编码一次，完整来源进入摘要，紧凑落盘仍不复制来源全文。 */
    fun prepare(input: Input): Prepared {
        val encoding = PreparedEncoding()
        headerFragments(input.record).forEach(encoding::append)
        encoding.appendValue(input)
        encoding.append("}")
        return encoding.finish()
    }

    /** 记录头保持既有 key/kind/value 排序，各输出路径共享同一规范编码。 */
    private fun headerFragments(record: SyncSnapshotRecord): Sequence<String> = sequence {
        yield("{\"key\":")
        yield(SyncOperationCanonicalizer.canonicalValue(JsonPrimitive(record.key)))
        yield(",\"kind\":")
        yield(SyncOperationCanonicalizer.canonicalValue(JsonPrimitive(record.kind)))
        yield(",\"value\":")
    }

    /** 来源字段插回其排序位置；未携带来源时保留原有字段，包括显式 null。 */
    private fun valueFragments(input: Input): Sequence<String> = sequence {
        var separator = ""
        yield("{")
        for (member in valueMembers(input)) {
            yield(separator)
            yield(member.name)
            yield(":")
            separator = ","
            yield(member.value)
        }
        yield("}")
    }

    /** 显式 null 和扩展成员按旧编码处理，仅真实规范来源片段标记为紧凑省略。 */
    private fun valueMembers(input: Input): Sequence<Member> = sequence {
        val value = input.record.value
        val keys = if (input.source == null) value.keys else value.keys - "sourceOperation" + "sourceOperation"
        for (key in keys.sorted()) {
            val source = key == "sourceOperation" && input.source != null
            yield(Member(SyncOperationCanonicalizer.canonicalValue(JsonPrimitive(key)),
                if (source) checkNotNull(input.source) else SyncOperationCanonicalizer.canonicalValue(value.getValue(key)), source))
        }
    }

    /** 状态只属于单条记录；从同一规范成员同时构建原完整摘要和紧凑文本。 */
    private class PreparedEncoding {
        private val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        private val stored = StringBuilder()

        /** 非来源片段同时写入两条输出，避免再次转义/编码字段值。 */
        fun append(text: String) {
            digest.update(text.toByteArray(Charsets.UTF_8))
            stored.append(text)
        }

        /** 原完整值和紧凑值分别维护边界，来源省略不能留下多余逗号。 */
        fun appendValue(input: Input) {
            var wireSeparator = ""
            var storedSeparator = ""
            append("{")
            for (member in valueMembers(input)) {
                digest.update(wireSeparator.toByteArray(Charsets.UTF_8))
                digest.update(member.name.toByteArray(Charsets.UTF_8))
                digest.update(":".toByteArray(Charsets.UTF_8))
                digest.update(member.value.toByteArray(Charsets.UTF_8))
                wireSeparator = ","
                if (member.source) continue
                stored.append(storedSeparator).append(member.name).append(":").append(member.value)
                storedSeparator = ","
            }
            append("}")
        }

        /** 结束当前记录后返回不可变结果，不把编码缓冲保留至下一条记录。 */
        fun finish(): Prepared = Prepared(digest.digest().toHexString(), stored.toString())
    }
}
