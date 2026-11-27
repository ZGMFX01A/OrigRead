package me.ash.reader.infrastructure.storage

/** 缺失字段沿用旧默认值；已有非法字段必须报错，避免回写抹掉原设置。 */
internal inline fun <reified T : Enum<T>> storedEnum(value: String?, fallback: T): T {
    if (value.isNullOrEmpty()) return fallback
    return enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalStateException("Invalid stored ${T::class.java.simpleName}: $value")
}
