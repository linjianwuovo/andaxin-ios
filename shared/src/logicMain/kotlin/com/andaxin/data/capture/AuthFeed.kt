package com.andaxin.data.capture

import com.andaxin.core.net.Crypto
import com.andaxin.core.net.HttpBody
import com.andaxin.core.net.HttpCall
import com.andaxin.core.net.HttpEngine
import com.andaxin.core.net.HttpEngineConfig
import com.andaxin.core.net.createHttpEngine
import com.andaxin.core.json.JSONObject

/**
 * 登录与刷新 token。
 *
 * 形状来自两处独立取证，都不经过任何人的源码（详见 `core/net/Crypto.kt` 顶上那段）：
 * 17:12 抓到安小信自己发的 `POST mobile/getToken.do`，以及 16:47 本机按同一形状直发拿到的响应。
 *
 * 请求  POST https://in.aiit.edu.cn/zhxy-information/mobile/getToken.do
 *       form: userCode、password=RSA(公钥见 Crypto)/Base64(口令)
 * 响应  { "flag": true, "rows": [], "data": { "token": {
 *           access_token(36) / refresh_token(36) / expires_in=28800 /
 *           refresh_token_timeout=2592000 / userCode / userName / userType /
 *           fileAddress / access_pass_time / refresh_pass_time /
 *           passwordUpdated / phoneStatus } } }
 *
 * 两个坑：① token 藏在 `data.token` 二层，只钻根节点会静默拿到空；
 * ② 字段是 snake_case，别按驼峰找 `accessToken`，找不到就当登录失败。
 */

const val AUTH_BASE = "https://in.aiit.edu.cn/zhxy-information"


/**
 * 登录这一类失败的两种结局，必须分开 —— 因为它决定「记住的那份口令还要不要留」。
 *
 * [AuthRejected] 是学校**答了、并且说不行**（口令错了、要先改口令、账号被锁）。这时候本机那份
 * 密文已经没有价值，留着它每次过闸门都去撞一遍服务器，该忘掉。
 *
 * [AuthUnreachable] 是根本没问成（连不上、网关给了一坨 HTML、结构不对）。这种失败**不能**把口令擦掉：
 * 一次网络抖动并不说明口令错了，而擦掉它等于永久废掉自动重登 —— 登录页上那颗一键重登的键会消失，
 * 屏幕上只留一句"登不进去"，可坏的根本不是口令。
 */
class AuthRejected(override val message: String) : Exception(message)

class AuthUnreachable(override val message: String) : Exception(message)

class AuthFeed {

    private val engine: HttpEngine = createHttpEngine(
        HttpEngineConfig(connectTimeoutMs = 15_000, readTimeoutMs = 20_000),
    )

    suspend fun login(userCode: String, password: String): Result<TokenBundle> =
        post("$AUTH_BASE/mobile/getToken.do", "userCode" to userCode, "password" to Crypto.encryptPassword(password))

    /**
     * 过期前拿 refresh_token 换新的；换不动就退回登录页，别静默续。
     *
     * **这条现在实际是打不通的，别把它当已接功能。** 10-19 20:1x 我拿自己账号的真 refresh_token
     * 从本机直发验过：`mobile/refresh.do` 回 404（一份 3218 字节的 HTML 错误页），蛇形/驼峰字段、
     * GET、带 `token` 头、带 `X-Requested-With`、带 Referer/Origin 六种打法全一样；同一个 base 下
     * `mobile/getToken.do` 空 POST 回的是 200 + `{"flag":false,"code":"S9999999"}`，`mobile/valid.do`
     * 也回 404 —— 所以不是网关在拦、也不是我们请求的形状不对，是这两条路由在校方那边现在就不通。
     * URL 本身不是我猜的：安小信 base.apk 的 classes.dex 字符串表里就有
     * `https://in.aiit.edu.cn/zhxy-information/mobile/refresh.do`（同族一共五条：sms / valid / logout /
     * refresh / getToken）。**官方包里有这条，不等于服务端现在接着。**
     *
     * 留着它是因为：① 哪天校内网/校方改版把它接回去了，这里一行都不用改；② 失败路径是有原因的
     * 降级，不是静默装死。所以 `TokenKeepAlive` 换不动时会把人请回登录页并写明原因。
     */
    suspend fun refresh(refreshToken: String): Result<TokenBundle> =
        post("$AUTH_BASE/mobile/refresh.do", "refresh_token" to refreshToken)

    private suspend fun post(url: String, vararg fields: Pair<String, String>): Result<TokenBundle> {
        val reply = runCatching {
            engine.execute(
                HttpCall(
                    url = url,
                    headers = listOf("Accept" to "application/json"),
                    body = HttpBody.Form(fields.toList()),
                ),
            )
        }
        return try {
            val resp = reply.getOrThrow()
            val body = resp.bodyText
            if (!resp.isSuccessful) {
                // 非 2xx 不算"学校否掉了口令"：网关/服务器的问题不该把记住的口令擦掉
                return Result.failure(AuthUnreachable("学校那边回了 ${resp.status}"))
            }
            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: return Result.failure(AuthUnreachable("回来的不是 JSON，可能被网关拦了"))
            // flag:false 就是学校自己的"不行"。空 POST 也回这个形状（`{"flag":false,"code":"S9999999"}`，
            // 10-19 现打），但它不带 msg —— 没 msg 不等于没答，照样按否掉处理。
            if (!root.optBoolean("flag", false)) {
                return Result.failure(
                    AuthRejected(
                        root.optString("msg").ifBlank { "账号或口令不对，或者要先改口令" }
                    )
                )
            }
            // token 在 data.token 这一层，只钻根节点会静默拿到空
            val token = root.optJSONObject("data")?.optJSONObject("token")
                ?: return Result.failure(AuthUnreachable("没拿到 token：回来的结构不对"))
            val access = token.optString("access_token")
            if (access.isBlank()) {
                return Result.failure(AuthRejected("账号或口令不对，或者要先改口令"))
            }
            Result.success(
                TokenBundle(
                    accessToken = access,
                    refreshToken = token.optString("refresh_token"),
                    userCode = token.optString("userCode"),
                    userName = token.optString("userName"),
                    userType = token.optString("userType", "1"),
                    expiresInSeconds = token.optLong("expires_in", 28800L),
                )
            )
        } catch (e: Exception) {
            Result.failure(AuthUnreachable("连不上学校：" + (e.message ?: e::class.simpleName)))
        }
    }
}