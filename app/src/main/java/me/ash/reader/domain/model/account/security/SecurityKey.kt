package me.ash.reader.domain.model.account.security

import com.google.gson.Gson

abstract class SecurityKey {

    fun <T> decode(value: String?, classOfT: Class<T>): T =
        Gson().fromJson(DESUtils.decrypt(value?.ifEmpty { DESUtils.empty } ?: DESUtils.empty), classOfT)

    override fun toString(): String {
        return DESUtils.encrypt(Gson().toJson(this))
    }

    override fun equals(other: Any?): Boolean {
        return other != null && javaClass == other.javaClass &&
            Gson().toJson(this) == Gson().toJson(other)
    }

    override fun hashCode(): Int {
        return 31 * javaClass.hashCode() + Gson().toJson(this).hashCode()
    }
}
