package me.ash.reader.infrastructure.sync.core

/** 两个回调均在副本事务外运行；只有真实提交的行字节才能减少剩余来源预约。 */
data class SyncSourceCopyProgress(val beforeBatch: () -> Unit, val committed: (String, Long) -> Unit)
