package com.andaxin.core.net

import kotlinx.coroutines.CoroutineDispatcher

/*
 * HTTP 缝：业务代码（School / FifSession / CampusCardClient 这些）将来只认这一份类型，
 * 底下各平台各给一台引擎 —— Android 沿用 app 里验证过的 OkHttp（报文路径不变），
 * iOS 用 Ktor+Darwin。设计输入来自现网读过的两处：
 *   School 要 form / json / 单文件 multipart 三种体、每发可单独限时、回信封看 flag；
 *   FifSession 要**关掉自动重定向**自己读 302 的 Location、还有一个进程内 cookie 罐。
 */

/** 请求体。三种，够用就好 —— 新增体种类之前先想想是不是协议层真多了一种。 */
sealed interface HttpBody {
    /** application/x-www-form-urlencoded。字段顺序保留（校方那套身份字段就讲究顺序语义）。 */
    data class Form(val fields: List<Pair<String, String>>) : HttpBody

    /** 原样字节体（fdygl 的 JSON 体走这个，content-type 由调用方给）。 */
    data class Text(val content: String, val contentType: String) : HttpBody

    /** 单文件 multipart（fdygl 传照片、报修传图那一族：一个字段名一个文件）。 */
    data class Upload(val field: String, val fileName: String, val mime: String, val bytes: ByteArray) : HttpBody
}

data class HttpCall(
    val method: String = "POST",
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: HttpBody? = null,
)

class HttpReply(
    val status: Int,
    /** 全部响应头摊平成键值对；同名头（Set-Cookie）出现多次就有多对。名字保原样大小写。 */
    val headers: List<Pair<String, String>>,
    val bodyText: String,
) {
    val isSuccessful: Boolean get() = status in 200..299
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    fun headers(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
}

/** 引擎按这一份配置造；School（跟重定向）和 FifSession（不跟、带 cookie 罐）是两台实例。 */
data class HttpEngineConfig(
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 20_000,
    val followRedirects: Boolean = true,
    val cookieStore: CookieStore? = null,
)

interface HttpEngine {
    /** 网络层失败直接抛（调用方 School 那层统一 catch 转 Result）；HTTP 4xx/5xx 不抛，回给调用方看。 */
    suspend fun execute(call: HttpCall, timeoutMs: Long? = null): HttpReply
}

/** 注意：expect 不许带默认参数，配置要传就传全（调用点都是显式给配置的，不留含糊）。 */
expect fun createHttpEngine(config: HttpEngineConfig): HttpEngine

/** 过期时间用 Long.MAX_VALUE 表示会话 cookie（跟 okhttp 的口径一致）。 */
class RawCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtMillis: Long,
)

/** 引擎收到 Set-Cookie 后回调 save；发请求前用 load 要一份该 host/path 该带的 cookie。 */
interface CookieStore {
    fun save(host: String, cookies: List<RawCookie>)
    fun load(host: String, path: String): List<RawCookie>
}

/** okhttp Cookie.matches 的那套域/路径匹配，口径搬过来。 */
fun RawCookie.matches(host: String, path: String): Boolean {
    val d = domain.removePrefix(".")
    val hostOk = host == d || host.endsWith(".$d")
    if (!hostOk) return false
    if (path.isEmpty()) return this.path == "/"
    val p = if (path.endsWith("/")) path.dropLast(1) else path
    val cp = if (this.path.endsWith("/")) this.path.dropLast(1) else this.path
    return p == cp || (p.startsWith("$cp/"))
}

/**
 * 和 java.net.URLEncoder.encode(s, "UTF-8") 逐字节同口径：字母数字和 . - * _ 之外全编，
 * 空格变 +，百分号大写十六进制。School 那套 `_userName` 的"一次编码"语义全靠这个不出偏差。
 */
fun formUrlEncode(s: String): String {
    if (s.isEmpty()) return ""
    val hex = "0123456789ABCDEF"
    val out = StringBuilder(s.length + 16)
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        when {
            c == ' '.code -> out.append('+')
            c in 48..57 || c in 65..90 || c in 97..122 -> out.append(c.toChar())
            c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> out.append(c.toChar())
            else -> out.append('%').append(hex[c ushr 4]).append(hex[c and 0xF])
        }
    }
    return out.toString()
}

/**
 * java.net.URLDecoder.decode(s, "UTF-8") 的同口径解码：+ 还原成空格，%XX 还原成字节再按 UTF-8 拼回。
 * 遇到非法序列就原样保留那三个字符（runCatching 的老行为也是带回原文）。
 */
fun formUrlDecode(s: String): String {
    if (!s.contains('%') && !s.contains('+')) return s
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' -> { bytes.add(' '.code.toByte()); i++ }
            c == '%' -> {
                val hex = s.substring(i + 1, minOf(i + 3, s.length))
                val v = hex.toIntOrNull(16)
                if (hex.length == 2 && v != null) {
                    bytes.add(v.toByte()); i += 3
                } else {
                    bytes.add('%'.code.toByte()); i++
                }
            }
            else -> {
                // 非 ASCII 原样进（不可能是编码产物，保原样）
                if (c.code < 128) bytes.add(c.code.toByte())
                else return s
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
