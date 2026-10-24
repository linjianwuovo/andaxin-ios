package com.andaxin.data.capture

import com.andaxin.core.concurrency.nowMillis
import com.andaxin.core.json.JSONObject
import com.andaxin.core.net.CookieStore
import com.andaxin.core.net.HttpCall
import com.andaxin.core.net.HttpEngine
import com.andaxin.core.net.HttpEngineConfig
import com.andaxin.core.net.RawCookie
import com.andaxin.core.net.createHttpEngine
import com.andaxin.core.net.formUrlEncode
import com.andaxin.core.net.matches
import com.andaxin.core.store.SessionStore
import com.andaxin.core.store.Store
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException

/*
 * AI 课堂那套（FIF 平台）的会话层。
 *
 * 这一层单独开一个文件，是因为它跟 in.aiit 那一族**完全是两套鉴权**：
 * 校内的 access_token 不能直接拿去问 FIF，要先经过一次单点登录换成 FIF 自己的 JWT，
 * 而 FIF 主站那几发认的就两个头：`authorization: Basic <jwt>` + `Visit-Type: mobile`。
 * 少一个它不报 401，就给你 HTTP 200 + `data:null`，看着像"这门课没内容"。
 *
 * 【取证 · 归属按本仓的三等同规矩标】
 * - ③（目标 App 发出、被我们自己抓到的报文）：SSO 那三跳的链路形状、`getAiktUserIdByMemberId`
 *   的响应骨架、`authorization: Basic <jwt>` + `Visit-Type: mobile` 这对头 ——
 *   出自 2026-10-13 那一轮真机抓包（清点表在 `lxtmp/契约_AI课堂抓包.md`）。
 * - ②（我们自己的客户端发出去、看到回的）：**10-20 上午整条链自己现打过一遍**
 *   （`lxtmp/adx_probe_fif.py`、`adx_probe_fif2.py`、`adx_probe_fif3.py`，用的是回放换来的活 token）。
 *   三跳全通，`myClassroom` 回 15 门课，签到两发、`getOneCourse`、`getFirstPageOfCourse`、
 *   直录播 76 条都拿到了真数据。这一层下面的注释凡写"实测"都是指这一轮。
 *   **唯一没验成的**：手机里这个 App 自己走一遍（PC 上通了，界面上还没读到过）。
 *
 * 平移注（2026-10-24）：底层 OkHttp 换共享层的 HTTP 缝引擎（followRedirects=false，cookie 罐
 * 自己实现 CookieStore）——Android 端实际仍是那台 OkHttp 池，报文路径不变；withIo 那层不需要了，
 * 引擎自己管线程。Behavior 口径（超时、UA、头顺序）逐条照抄。
 */

/**
 * FIF 那侧认的几个 id。名字像，来源完全不同，混了就是一脸问号。
 *
 * 【取证 · ② 我今天现打到的】`getAiktUserIdByMemberId` 回的 `data{}` 一条不差是：
 * `id="<8 位 FIF 账号 id>"(8) userType="student" name=<姓名> avatar phone="" email="" collegeId="3212"(4)
 * gender number="<10 位学号>"(10) userName="aiit+学号"(14) userId=<32 hex> epasUserId=""
 * schoolId="2811000226000001678"(19) schoolName="安徽信息工程学院"`。
 * 注意 **`userName` 不是学号**，是 `aiit` 前缀 + 学号那个 14 位登录名；10 位学号在 `number`。
 * 清点表里 `userName:str(14)` 那几发（B21/C28/C31）要的就是前者。
 */
data class FifIds(
    /** 短学号（8 位，样例 `<8 位 FIF 账号 id·值从略>`）。签到那两发的 `id`、`studentId` 用它。 */
    val studentId: String,
    /** 登录名（14 位，`aiit` + 学号）。微课、作业列表那几发的 `userName` 用它。 */
    val userName: String,
    val schoolId: String,
    /** 32 位成员 id，从 FIF JWT 的第二段里解出来。课件/小组/课表那几发用它当 `userId`/`mid`/`studnetId`。 */
    val memberUserId: String,
)

class FifSession(
    private val store: Store,
    private val school: SessionStore,
) {

    private val mutex = Mutex()

    /** cookie 罐的锁：原来用 JVM 的 synchronized，共享层换三平台 Lock。 */
    private val jarLock = com.andaxin.core.concurrency.Lock()

    /**
     * 只活在进程里的 cookie 罐。按 host 分桶，同名同 path 同 domain 覆盖，过期即丢 ——
     * OkHttp 自带的示例实现是全局一张 map，跨域名会串。
     */
    private val jar = MemJar()

    private val engine: HttpEngine = createHttpEngine(
        HttpEngineConfig(
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
            // 第一跳要自己读 302 的 Location，不能让引擎替我跟过去
            followRedirects = false,
            cookieStore = jar,
        ),
    )

    /** 进程内 cookie 罐：引擎收发时回调。口径照原来那只 okhttp CookieJar 抄的。 */
    private class MemJar : CookieStore {
        val map = HashMap<String, MutableList<RawCookie>>()
        private val mapLock = com.andaxin.core.concurrency.Lock()

        override fun save(host: String, cookies: List<RawCookie>) {
            mapLock.withLock {
                val list = map.getOrPut(host) { mutableListOf() }
                cookies.forEach { c ->
                    list.removeAll { old -> old.name == c.name && old.path == c.path && old.domain == c.domain }
                    list.add(c)
                }
            }
        }

        override fun load(host: String, path: String): List<RawCookie> =
            mapLock.withLock { map[host].orEmpty().filter { it.matches(host, path) } }
    }

    /**
     * 有没有一把还没过期的 FIF token。
     *
     * 【取证 · ② 我今天自己发出去并看到的】10-20 上午拿真账号回放换来的校内 token 现打了一遍
     * 单点登录三跳（`lxtmp/adx_probe_fif.py`）：
     * ① 第一跳确实回 302，`Location` 里挂着 333 位的 JWT，payload 是
     *    `{realName, schoolId, iss:"fifac", id(19), exp, username:"aiit+学号", memberId(32)}`；
     * ② **第二跳 `studycenter-nh5/?token=…` 回 200，但一条 Set-Cookie 都没有** ——
     *    抓包清点里那句"11 个流文件扫过，没有任何一条响应下发过 SESSION"是真的；
     * ③ 后面 `getAiktUserIdByMemberId`、`termList`、`myClassroom`、签到两发、`getOneCourse`、
     *    `getFirstPageOfCourse` **全是在 cookie 罐为空的状态下打通的**，只要
     *    `authorization: Basic <jwt>` + `Visit-Type: mobile` 这两个头。
     *
     * 所以判有效只看 JWT 的 `exp`。上一版这里还要求"罐里得有一条 SESSION"，那是照官方
     * WebView 的样子抄的（它那个 SESSION 是前端自己写进 CookieManager 的，清点表也标了是推断），
     * 服务端既然不发，这条检查就永远不成立 —— 后果不是报错，是**每进一栏都重跑一遍完整 SSO**，
     * 白多三个来回。cookie 罐留着不删：官方那侧确实带，我们带着也无害。
     */
    suspend fun isSessionValid(): Boolean {
        val token = store.peekString(store.snapshot(), KEY_FIF_TOKEN).orEmpty()
        if (token.isBlank()) return false
        return jwtExpiresAt(token)?.let { nowMillis() / 1000 + SKEW < it } ?: true
    }

    suspend fun ids(): FifIds? {
        val p = store.snapshot()
        val student = store.peekString(p, KEY_STUDENT_ID).orEmpty()
        val user = store.peekString(p, KEY_USER_NAME).orEmpty()
        val schoolId = store.peekString(p, KEY_SCHOOL_ID).orEmpty()
        if (student.isBlank() && user.isBlank()) return null
        return FifIds(
            studentId = student,
            userName = user,
            schoolId = schoolId,
            memberUserId = store.peekString(p, KEY_MEMBER_ID).orEmpty(),
        )
    }

    /** 主站那套的头：`authorization: Basic <jwt>` + `Visit-Type: mobile`。 */
    suspend fun authHeader(): String? = store.peekString(store.snapshot(), KEY_FIF_TOKEN)
        ?.takeIf { it.isNotBlank() }
        ?.let { "Basic $it" }

    /**
     * 把这一跳攒下来的 cookie 拼成一条 `name=value; name=value`，给 WebView 用。
     *
     * 为什么要这个：签到二维码解出来是个**网页**（`qrcodeV2/qrcodeHandler?token=…`），
     * 官方是拿带着 FIF 会话的 WebView 打开它、由服务端完成签到。我们 App 里那份会话
     * 只活在自己的 cookie 罐里，WebView 看不到 —— 不搬过去，页面就是没登录的样子。
     */
    fun cookieHeader(host: String): String =
        jarLock.withLock {
            jar.map.entries.firstOrNull { it.key.endsWith(host) }?.value.orEmpty()
                .filter { it.expiresAtMillis > nowMillis() }
                .joinToString("; ") { "${it.name}=${it.value}" }
        }

    /**
     * 资源中心（`resc.fifedu.com`）那发要的是**裸 JWT**：带 `Basic ` 前缀它不认，
     * 而且不能带 `Visit-Type`。主站的习惯直接搬过去，回的是 200 + `data:null`。
     */
    suspend fun bareJwt(): String? = store.peekString(store.snapshot(), KEY_FIF_TOKEN)
        ?.takeIf { it.isNotBlank() }

    /**
     * 保证有一把能用的 FIF 会话。已经有效就一个字节都不发。
     *
     * 拿锁之后**还要再查一遍**：首页预热和进页面加载曾经同一秒各跑了一遍完整 SSO，白多花 800ms。
     */
    suspend fun ensure(): Result<FifIds> {
        if (isSessionValid()) ids()?.let { return Result.success(it) }
        return mutex.withLock {
            if (isSessionValid()) {
                ids()?.let { return@withLock Result.success(it) }
            }
            performSso()
        }
    }

    /** 三跳。任何一跳失败都回 failure，带得上原因就带上，不静默降级。 */
    suspend fun performSso(): Result<FifIds> {
        val inner = school.identity()
            ?: return Result.failure(IOException("校内还没登录，FIF 的单点登录第一步就没有 access_token"))

        // 第一跳：aiitpass 换 FIF 的 JWT。token 值在 302 的 Location 的 query 里。
        val ssoUrl = "$HOST_PASS/iplat-pass-aiit/h5/login?" +
            "access_token=${urlEnc(inner.accessToken)}" +
            "&_userCode=${urlEnc(inner.userCode)}" +
            "&code=${urlEnc(inner.userCode)}" +
            "&userCode=${urlEnc(inner.userCode)}" +
            "&_userName=${urlEnc(inner.userName)}" +
            "&_userType=${urlEnc(inner.userType)}" +
            "&appId=${School.APP_ID}" +
            "&returnFromIscToAppFunc=ReturnDefault"
        val location = runCatching {
            engine.execute(get(ssoUrl)).header("Location")
        }.getOrElse { return Result.failure(it) }

        /*
         * 这里有个坑值得单独写一行：`?token=` 值为空时，query 解析出来是**空串不是 null**，
         * 只判 null 就会把"没拿到 token"当成登录成功存下来，之后每一发都回"鉴权失败 Token 为空"，
         * 再触发重登，形成几十次往返的慢循环。所以必须 isNotBlank 过滤。
         */
        val jwt = tokenFromLocation(location)
            ?: return Result.failure(IOException("单点登录没回 token（Location：${location?.take(90) ?: "没有跳转"}）"))

        // 第二跳：只为让服务器 Set-Cookie 出 SESSION
        runCatching { engine.execute(get("$HOST_STTP/studycenter-nh5/?token=${urlEnc(jwt)}&app=axx")) }

        // 第三跳：换三个 id。失败不致命 —— 课程列表照样能拉，只是签到/小组那几发没身份
        val ids = runCatching { fetchIds(jwt) }.getOrElse {
            store.setText(KEY_FIF_TOKEN, jwt)
            saveIdentityCookies(jwt, ids = null)
            return Result.failure(IOException("FIF 身份接口没通：${it.message ?: it::class.simpleName}"))
        }
        store.setMany(
            KEY_FIF_TOKEN to jwt,
            KEY_STUDENT_ID to ids.studentId,
            KEY_USER_NAME to ids.userName,
            KEY_SCHOOL_ID to ids.schoolId,
            KEY_MEMBER_ID to ids.memberUserId,
        )
        saveIdentityCookies(jwt, ids)
        return Result.success(ids)
    }

    suspend fun clear() {
        jarLock.withLock { jar.map.clear() }
        store.setMany(
            KEY_FIF_TOKEN to null,
            KEY_STUDENT_ID to null,
            KEY_USER_NAME to null,
            KEY_SCHOOL_ID to null,
            KEY_MEMBER_ID to null,
        )
    }

    private suspend fun fetchIds(jwt: String): FifIds {
        var last: Throwable? = null
        // 退避三次：600ms、1200ms。这一发挂了只降级显示，不该把整块 AI 课堂带走
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(600L * attempt)
            runCatching {
                val body = postForm(
                    "$HOST_STTP/studycenter/mobile/common/getAiktUserIdByMemberId",
                    emptyList(),
                    "Basic $jwt",
                )
                val root = JSONObject(body)
                val data = root.optJSONObject("data") ?: JSONObject()
                FifIds(
                    studentId = data.optString("id"),
                    userName = data.optString("userName").ifBlank { school.identity()?.userCode.orEmpty() },
                    schoolId = data.optString("schoolId"),
                    memberUserId = memberFromJwt(jwt).orEmpty(),
                )
            }.onSuccess { return it }.onFailure { last = it }
        }
        throw last ?: IOException("身份接口三次都没通")
    }

    /** 中文姓名进 cookie 前必须转 ASCII，否则引擎会因非法字符拒掉整条 cookie，把整条登录链炸掉。 */
    private fun saveIdentityCookies(jwt: String, ids: FifIds?) {
        val name = ids?.userName?.takeIf { it.isNotBlank() } ?: return
        val pairs = listOf(
            "id" to ids.studentId,
            "studentId" to ids.studentId,
            "currentUserName" to formUrlEncode(name),
        )
        val list = pairs.filter { it.second.isNotBlank() }.map { (k, v) ->
            RawCookie(k, v, "fifedu.com", "/", Long.MAX_VALUE)
        }
        jarLock.withLock {
            val old = jar.map[HOST_STTP].orEmpty()
            jar.map[HOST_STTP] = (
                old.filterNot { n -> list.any { it.name == n.name } } + list
                ).toMutableList()
        }
    }

    /**
     * 资源中心（`resc.fifedu.com`）那一发专用：带**裸 JWT**、**不带 `Visit-Type`**、
     * 带 `Referer/Origin: mresc.fifedu.com`。照主站习惯带头会回 200 + `data:null`。
     * 【取证】③ 出自旧项目 2026-10-13 抓到的官方那一发。
     */
    suspend fun getResc(url: String, jwt: String): String {
        val reply = engine.execute(
            HttpCall(
                method = "GET",
                url = url,
                headers = listOf(
                    "User-Agent" to WEB_UA,
                    "Accept" to "application/json, text/plain, */*",
                    "authorization" to jwt,
                    "X-Requested-With" to "com.andaxin",
                    "Referer" to "$HOST_RESC/",
                    "Origin" to HOST_RESC,
                ),
            ),
        )
        if (!reply.isSuccessful) throw IOException("资源中心回了 ${reply.status}")
        return reply.bodyText
    }

    /**
     * `adapt-app` 那一族用的 GET：**裸 JWT 放在 Authorization 里（不带 `Basic ` 前缀）**、
     * 带 `Referer: https://sttp.fifedu.com/adaptive/`、**不带 `Visit-Type`**。
     *
     * 【取证】③ 抓包里官方那三发（`courseDetail`/`newCourseChapterList`/`getUserDetail`）的请求头逐条读出来就是这个样子；
     * ② 我们照主站习惯发 `Basic <jwt>` 的时候，它回 `status:401` +
     * 「The string '…' doesn't have a valid JSON format」—— 它把 "Basic " 后那段当 base64 解，
     * 所以这一族要的是一串能被 base64 解开的东西，而裸 JWT 恰好能过（`lxtmp/_adapt_probe.txt`）。
     */
    suspend fun getAdapt(url: String, jwt: String, referer: String): JSONObject {
        val reply = engine.execute(
            HttpCall(
                method = "GET",
                url = url,
                headers = listOf(
                    "User-Agent" to WEB_UA,
                    "Accept" to "application/json, text/plain, */*",
                    "Authorization" to jwt,
                    "Referer" to referer,
                    "X-Requested-With" to "cn.edu.aiit.axx",
                ),
            ),
        )
        if (!reply.isSuccessful) throw IOException("adapt-app 回了 ${reply.status}")
        return JSONObject(reply.bodyText)
    }

    private fun get(url: String): HttpCall = HttpCall(
        method = "GET",
        url = url,
        headers = listOf("User-Agent" to WEB_UA),
    )

    suspend fun postForm(url: String, fields: List<Pair<String, String>>, auth: String?): String {
        val headers = buildList {
            add("User-Agent" to WEB_UA)
            add("Accept" to "application/json, text/plain, */*")
            if (auth != null) add("authorization" to auth)
            add("Visit-Type" to "mobile")
        }
        val reply = engine.execute(HttpCall(url = url, headers = headers, body = com.andaxin.core.net.HttpBody.Form(fields)))
        if (!reply.isSuccessful) throw IOException("FIF 回了 ${reply.status}")
        return reply.bodyText
    }

    /** 带 JWT 发一发 GET，回根节点。 */
    suspend fun getJson(url: String, auth: String? = null): JSONObject {
        val headers = buildList {
            add("User-Agent" to WEB_UA)
            add("Accept" to "application/json, text/plain, */*")
            add("Visit-Type" to "mobile")
            (auth ?: authHeader())?.let { add("authorization" to it) }
        }
        val reply = engine.execute(HttpCall(method = "GET", url = url, headers = headers))
        if (!reply.isSuccessful) throw IOException("FIF 回了 ${reply.status}")
        return JSONObject(reply.bodyText)
    }

    private companion object {
        const val HOST_PASS = "https://aiitpass.fifedu.com"
        const val HOST_STTP = "https://sttp.fifedu.com"

        /** 播放页真正的 referer 是这个子域，不是 resc 本身。 */
        const val HOST_RESC = "https://mresc.fifedu.com"

        /** 手机 WebView 那一串。FIF 有些口认 UA，给 okhttp 默认串会被当爬虫。 */
        const val WEB_UA =
            "Mozilla/5.0 (Linux; Android 17; M2011K2C Build/CP2A.260605.016; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/149.0.7827.91 Mobile Safari/537.36"

        const val SKEW = 60L

        const val KEY_FIF_TOKEN = "fif.token"
        const val KEY_STUDENT_ID = "fif.studentId"
        const val KEY_USER_NAME = "fif.userName"
        const val KEY_SCHOOL_ID = "fif.schoolId"
        const val KEY_MEMBER_ID = "fif.memberId"

        fun urlEnc(s: String): String = formUrlEncode(s)

        /**
         * Location 里取 token：正规按 query 解（split & 找 token= 再 formUrlDecode），
         * 解不出空值退回正则（那条 Location 见过不规范编码）。
         */
        fun tokenFromLocation(location: String?): String? {
            if (location.isNullOrBlank()) return null
            val fromUrl = location.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("token=") }
                ?.removePrefix("token=")
                ?.let { runCatching { com.andaxin.core.net.formUrlDecode(it) }.getOrNull() }
            return fromUrl?.takeIf { it.isNotBlank() }
                ?: Regex("[?&]token=([^&]+)").find(location)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        }

        /** JWT 第二段 base64url 解出来，取 `memberId` 或 `memberUserId`。 */
        @OptIn(ExperimentalEncodingApi::class)
        fun memberFromJwt(jwt: String): String? {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val json = runCatching {
                Base64.UrlSafe.decode(
                    parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='),
                ).decodeToString()
            }.getOrNull() ?: return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            return listOf("memberId", "memberUserId", "userId").asSequence()
                .map { o.optString(it) }
                .firstOrNull { it.isNotBlank() && it.length >= 16 }
        }

        /** 解 JWT 的 `exp`（秒）。解不出来按"未到期"处理，别因为解析失败把人关在登录循环里。 */
        @OptIn(ExperimentalEncodingApi::class)
        fun jwtExpiresAt(jwt: String): Long? {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val json = runCatching {
                Base64.UrlSafe.decode(
                    parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='),
                ).decodeToString()
            }.getOrNull() ?: return null
            return runCatching { JSONObject(json).optLong("exp") }.getOrNull()?.takeIf { it > 0 }
        }
    }
}
