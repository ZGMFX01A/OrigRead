package me.ash.reader.domain.model.feed

import androidx.room.TypeConverter

/**
 * 资讯来源类型，用于决定同步时采用哪一种内容抓取方式。
 */
enum class SourceType {
    RSS,
    WEBSITE,
    JSON,
}

/**
 * 将来源类型以稳定的字符串形式存入 Room 数据库。
 */
class SourceTypeConverters {

    @TypeConverter
    fun toSourceType(value: String): SourceType =
        SourceType.entries.firstOrNull { it.name == value } ?: SourceType.RSS

    @TypeConverter
    fun fromSourceType(sourceType: SourceType): String = sourceType.name
}
