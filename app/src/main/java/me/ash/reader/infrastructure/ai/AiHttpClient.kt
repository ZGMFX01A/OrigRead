package me.ash.reader.infrastructure.ai

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.di.UserAgentInterceptor
import okhttp3.OkHttpClient

/** AI 请求使用系统默认 TLS，避免凭据进入兼容旧站点的宽松网络客户端。 */
@Singleton
class AiHttpClient internal constructor(val client: OkHttpClient) {
    @Inject constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .eventListenerFactory { AiPerfEventListener() }
            .addNetworkInterceptor(UserAgentInterceptor)
            .build()
    )

    val streamingClient: OkHttpClient =
        client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
}

