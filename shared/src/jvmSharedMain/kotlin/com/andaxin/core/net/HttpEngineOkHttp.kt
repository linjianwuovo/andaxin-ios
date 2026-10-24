package com.andaxin.core.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Android/JVM 的引擎：就是 app 里原来那台 OkHttp 的搬运 —— 超时口径（连 15s / 读 20s）、
 * form 体的字段顺序、multipart 的字段名、每发限时走 call.timeout()，全按原样。
 * Android 的明文放行不在这层管：OkHttp 具备明文能力，允不允许由平台那份
 * network_security_config / ATS 决定（这是 10-20 那次教训的分层，别改回去）。
 */

actual fun createHttpEngine(config: HttpEngineConfig): HttpEngine = OkHttpEngine(config)

/**
 * 进程级底座：所有引擎都从这一台 newBuilder() 出去，共享连接池和调度线程
 * （对应 app 里 `Http.client` 那个单例的口径；超时那些每台引擎自己覆写）。
 * 明文能力保持默认在 —— 允不允许由平台层决定，这里不做任何 connectionSpec 手脚。
 */
internal val engineBaseClient: OkHttpClient by lazy { OkHttpClient() }

private class OkHttpEngine(config: HttpEngineConfig) : HttpEngine {

    private val client: OkHttpClient = engineBaseClient
        .newBuilder()
        .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(config.followRedirects)
        .followSslRedirects(config.followRedirects)
        .apply {
            config.cookieStore?.let { store -> cookieJar(StoreJar(store)) }
        }
        .build()

    override suspend fun execute(call: HttpCall, timeoutMs: Long?): HttpReply = withContext(Dispatchers.IO) {
        val url = call.url.toHttpUrl()
        val body = when (val b = call.body) {
            null -> null
            is HttpBody.Form -> FormBody.Builder().apply { b.fields.forEach { (k, v) -> add(k, v) } }.build()
            is HttpBody.Text -> b.content.toRequestBody(b.contentType.toMediaType())
            is HttpBody.Upload -> MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(b.field, b.fileName, b.bytes.toRequestBody(b.mime.toMediaType()))
                .build()
        }
        val request = Request.Builder()
            .url(url)
            .apply { body?.let { method(call.method, it) } ?: method(call.method, null) }
            .apply { call.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val okCall = client.newCall(request)
        if (timeoutMs != null) okCall.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        okCall.execute().use { resp ->
            HttpReply(
                status = resp.code,
                // okhttp 的 Headers 本来就是 (名, 值) 对的列表，同名头出现几次就有几对
                headers = buildList { for ((n, v) in resp.headers) add(n to v) },
                bodyText = resp.body?.string().orEmpty(),
            )
        }
    }

    /** okhttp 收到 Set-Cookie 会解析成 Cookie 再给我们 —— 顺手折成 RawCookie。 */
    private class StoreJar(private val store: CookieStore) : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.save(
                url.host,
                cookies.map { RawCookie(it.name, it.value, it.domain, it.path, it.expiresAt) },
            )
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.load(url.host, url.encodedPath).mapNotNull { c ->
                runCatching {
                    Cookie.Builder()
                        .name(c.name)
                        .value(c.value)
                        .domain(c.domain.removePrefix("."))
                        .path(c.path)
                        .expiresAt(c.expiresAtMillis)
                        .build()
                }.getOrNull()
            }
    }
}
