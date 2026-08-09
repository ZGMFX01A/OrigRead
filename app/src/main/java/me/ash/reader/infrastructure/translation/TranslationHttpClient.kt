package me.ash.reader.infrastructure.translation

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.di.UserAgentInterceptor
import okhttp3.OkHttpClient

/**
 * 翻译凭据只通过系统默认 TLS 客户端发送，不复用项目中兼容旧站点的宽松证书策略。
 */
@Singleton
class TranslationHttpClient @Inject constructor() {
    val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .addNetworkInterceptor(UserAgentInterceptor)
            .build()
}

