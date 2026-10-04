package me.ash.reader.infrastructure.sync.core

/** 只合并小语法片段，大字段和规范来源直接进入原有分块 UTF-8 编码器。 */
internal object SyncSnapshotTextFragments {
    /** 小片段暂存上界按字符计算；不截断大字段，也不保留完整 wire 副本。 */
    private const val SMALL_FRAGMENT_CHARS = 2048

    /** 原排序和行末换行不变，来源只借用已有不可变字符串。 */
    fun write(parts: Sequence<String>, consume: (String) -> Unit) {
        val pending = StringBuilder(SMALL_FRAGMENT_CHARS)
        fun flush() {
            if (pending.isEmpty()) return
            consume(pending.toString())
            pending.setLength(0)
        }
        for (part in parts) {
            SyncSnapshotCancellation.checkpoint()
            if (part.isEmpty()) continue
            if (part.length >= SMALL_FRAGMENT_CHARS) { flush(); consume(part); continue }
            if (pending.length + part.length > SMALL_FRAGMENT_CHARS) flush()
            pending.append(part)
        }
        flush()
        consume("\n")
    }
}
