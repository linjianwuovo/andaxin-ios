package com.andaxin.core.store

import com.andaxin.core.concurrency.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 会话与首次使用状态：全应用唯一一份登录态，落在 `andaxin` 那个 DataStore 文件里。
 *
 * `loggedIn` 是从"有没有 token"推出来的，不是另存的一个布尔 —— 免得出现
 * "token 已经清掉了但 loggedIn 还写着 true" 这种界面进不去、又 nowhere 可退的状态。
 * token 只存本机这一份，不进日志、不进仓、不进崩溃上报。
 *
 * 到点续期不在这里做，在 `TokenKeepAlive`：这一层只管存和读。
 */
data class Session(
    val onboarded: Boolean = false,
    val loggedIn: Boolean = false,
    val browsing: Boolean = false,
    val account: String = "",
    val userName: String = "",
    val dorm: String = "",
    /**
     * 本机存了加密后的登录口令，**而且这份口令还能用来重登**。
     *
     * 判据是"密文和学号都还在"，不是只看密文 —— 自动重登两样都要，只留着密文的那一份是解不开的孤儿
     * （10-21 04:4x 从米11 上读回来的正是这一份：`auth.pwBlob` 有值、`auth.userCode` 这个键不存在）。
     * 按"密文在不在"算，「我的」那一格会写着"已记住，到期或被顶号时能自己重登"，
     * 而登录页压根不给那颗键 —— 同一件事两屏互相打脸。
     * 能不能真解开是另一回事：`LoginScreen` 进那一屏时现解一次，解不开就当场忘掉这一份并说一句
     * （10-23 起口令直接填进登录页那一格，"记没记住"在屏幕上看得见）。
     */
    val remembered: Boolean = false,
    /** 上一次"记住密码"为什么没成（Keystore 报错原话）。成了就是 null。 */
    val rememberedWhy: String = "", 
    /**
     * 每次成功拿到新票都换一个值。界面用它当"重新拉一次"的 key ——
     * 理由写在 `SessionStore.save` 那段（冷启动自动重登和首页并发撞车那次）。
     */
    val generation: Long = 0L,
)

/**
 * 协议层要带的身份四件套。名字照校方字段来，别在中间层再翻一次命名。
 *
 * `userType` 是登录响应里那个 `userType`（学生是 "1"）。官方 App 自己也有发 "2" 的时候，
 * 10-23 逐屏抓完数出来了：scps 那 18 条带 `_userType` 的口里恰好三条发 2 ——
 * `msgCenter/mesDetailListNew`、`appService/checkServiceVersion`、`userLogin/click`，
 * 都是"从服务入口进去的那一侧"；App 外壳自己那 15 条全是 1。
 * 我们这边所有口都照登录响应原样带 1，目前条条回得来数据，**别跟着改成 2**。
 */
data class SchoolIdentity(
    val accessToken: String,
    val userCode: String,
    val userName: String,
    val userType: String,
)

class SessionStore(private val store: Store) {

    val state: Flow<Session> = store.raw.map { p ->
        Session(
            onboarded = store.peekString(p, KEY_ONBOARDED) == "1",
            loggedIn = !store.peekString(p, Store.KEY_TOKEN).isNullOrBlank(),
            browsing = store.peekString(p, KEY_BROWSING) == "1",
            account = store.peekString(p, Store.KEY_USER_CODE).orEmpty(),
            userName = store.peekString(p, KEY_USER_NAME).orEmpty(),
            dorm = store.peekString(p, KEY_DORM).orEmpty(),
            remembered = !store.peekString(p, KEY_PW_BLOB).isNullOrBlank() &&
                !store.peekString(p, Store.KEY_USER_CODE).isNullOrBlank(),
            rememberedWhy = store.peekString(p, KEY_PW_WHY).orEmpty(),
            generation = store.peekString(p, KEY_GEN)?.toLongOrNull() ?: 0L,
        )
    }

    /**
     * 登录成功后落盘。token 只存本机这一份文件里，不进日志、不进仓。
     *
     * 顺手把 `auth.generation` 一起写：界面要靠它知道"手里这把 token 换过了"。
     * 装机撞到的那条race 值得记一笔（10-20 晚，0.2-全接 第一次读屏）：冷启动时
     * `Gate` 的自动重登和首页那一路并发撞在一起 —— 首页四条里有三条先拿**旧**token 发出去，
     * 服务端回"没有访问权限"，于是屏幕上就写着"轮播图没拉到：登录已在别处被顶掉"，
     * 而同一屏的校历（后发的那条）却是真数据。看着像我们只接好了一半。
     * `loggedIn`/`userName` 这些字段在重登前后是一样的，界面订阅不到这次换票，
     * 所以留一个纯计数器性质的时间戳在这儿，谁要"重登之后重新拉一次"就 key 在它上面。
     * 存的是毫秒时间戳，不含任何凭据。
     */
    suspend fun save(token: com.andaxin.data.capture.TokenBundle) {
        store.setMany(
            Store.KEY_TOKEN to token.accessToken,
            KEY_REFRESH to token.refreshToken,
            Store.KEY_USER_CODE to token.userCode,
            KEY_USER_NAME to token.userName,
            KEY_USER_TYPE to token.userType,
            KEY_EXPIRES_AT to (nowMillis() / 1000 + token.expiresInSeconds).toString(),
            KEY_BROWSING to null,
            KEY_GEN to nowMillis().toString(),
        )
    }

    suspend fun refreshToken(): String? = store.peekString(store.snapshot(), KEY_REFRESH)

    /** 手里有没有 token。不读 Session 那条流，就是为了在协程里现查一次。 */
    suspend fun signedIn(): Boolean = !store.peekString(store.snapshot(), Store.KEY_TOKEN).isNullOrBlank()

    /**
     * 给协议层用的身份四件套。没登录给 null，让上层自己决定是退回登录页还是继续用占位数据。
     *
     * 校方那一圈 `.do` 每一条都要带这一套：`access_token` + `_userCode` + `userId` + `userCode` +
     * `_userName` + `_userType` + `appId`。少一个就回 `flag:false` 或者直接给你一坨 HTML。
     */
    suspend fun identity(): SchoolIdentity? {
        val p = store.snapshot()
        val token = store.peekString(p, Store.KEY_TOKEN)
        val code = store.peekString(p, Store.KEY_USER_CODE)
        if (token.isNullOrBlank() || code.isNullOrBlank()) return null
        return SchoolIdentity(
            accessToken = token,
            userCode = code,
            userName = store.peekString(p, KEY_USER_NAME).orEmpty(),
            userType = store.peekString(p, KEY_USER_TYPE) ?: "1",
        )
    }

    /** 还剩不到两小时就算过期（学校给 8 小时） */
    suspend fun tokenExpired(): Boolean {
        val until = store.peekString(store.snapshot(), KEY_EXPIRES_AT)?.toLongOrNull() ?: return true
        return nowMillis() / 1000 >= until - 7200
    }

    suspend fun agreeTerms() = store.setText(KEY_ONBOARDED, "1")

    /** 先不登录，用占位数据逛界面。真接上登录后这条路径就该被登录取代。 */
    suspend fun browseWithoutLogin() = store.setText(KEY_BROWSING, "1")

    /** 从"先不登录逛逛"退回登录那一屏。只清浏览标志，不动 token。 */
    suspend fun leaveBrowsing() = store.setText(KEY_BROWSING, null)

    suspend fun setDorm(value: String) = store.setText(KEY_DORM, value.ifBlank { null })

    /** 记住口令：只存 Keystore 加密后的 base64，明文一刻都不落盘。 */
    suspend fun rememberPassword(blob: String?) = store.setText(KEY_PW_BLOB, blob)

    suspend fun passwordBlob(): String? = store.peekString(store.snapshot(), KEY_PW_BLOB)

    /** 重登要用的学号。`identity()` 要 token，被顶号那会儿不能用它，所以单开一条只读账号。 */
    suspend fun accountNow(): String? =
        store.peekString(store.snapshot(), Store.KEY_USER_CODE)?.takeIf { it.isNotBlank() }

    suspend fun forgetPassword() {
        store.setText(KEY_PW_BLOB, null)
        store.setText(KEY_PW_WHY, null)
    }

    /** 存不下的原因也要落盘：这一格下次进来还得能说出为什么。 */
    suspend fun rememberPasswordWhy(why: String?) = store.setText(KEY_PW_WHY, why)

    /**
     * 清登录态。
     *
     * `keepPassword` 只有一种情况该给 true：**被顶号 / 到期，而本机记着口令、马上要用它重登**。
     * 那时候清掉口令就等于把自己锁在门外（`refresh.do` 服务端是死的，重登只能靠口令）。
     * 他自己按「退出登录」走的是默认 false —— 人都要退了，不该继续存着钥匙。
     */
    suspend fun signOut(keepPassword: Boolean = false) = store.setMany(
        Store.KEY_TOKEN to null,
        // 学号跟着口令走：留了口令却把学号擦掉，那份密文就成了解不开的孤儿 ——
        // `reloginFromVault()` 第一步要的就是学号。10-21 凌晨装机撞中的正是这个：
        // 会话掉了、「我的」上明明写着"已记住，到期或被顶号时能自己重登"，
        // 结果那颗「用这台机器记住的口令登进去」按下去只会说"登不进去"。
        Store.KEY_USER_CODE to if (keepPassword) store.peekString(store.snapshot(), Store.KEY_USER_CODE) else null,
        KEY_REFRESH to null,
        KEY_USER_NAME to null,
        KEY_USER_TYPE to null,
        KEY_EXPIRES_AT to null,
        KEY_BROWSING to null,
        KEY_PW_BLOB to if (keepPassword) store.peekString(store.snapshot(), KEY_PW_BLOB) else null,
    )

    private companion object {
        const val KEY_ONBOARDED = "app.onboarded"
        const val KEY_BROWSING = "app.browsing"
        const val KEY_DORM = "profile.dorm"
        const val KEY_REFRESH = "auth.refresh"
        const val KEY_USER_NAME = "auth.userName"
        const val KEY_USER_TYPE = "auth.userType"
        const val KEY_EXPIRES_AT = "auth.expiresAt"
        const val KEY_PW_BLOB = "auth.pwBlob"
        const val KEY_PW_WHY = "auth.pwWhy"

        /** 换票计数（存的是毫秒时间戳，不含凭据）。见 `Session.generation`。 */
        const val KEY_GEN = "auth.generation"
    }
}
