package com.andaxin.data.capture

import com.andaxin.core.net.HttpCall
import com.andaxin.core.net.HttpEngine
import com.andaxin.core.net.HttpEngineConfig
import com.andaxin.core.net.createHttpEngine
import com.andaxin.core.json.JSONObject
import kotlin.time.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/*
 * 安大信自己的检测更新。
 *
 * 【为什么换掉原来那一格】关于页以前那颗是 `appInfo/checkVersion.do` —— 校方给**安小信**用的通道，
 * 回的是它的 apk，对我们只剩"官方升到哪儿了、协议可能变了"那一点用处，不是给我们自己检查更新。
 *
 * 【渠道 · 10-23 深夜现打定死】读的是 GitHub 的 release 订阅 `releases.atom`，不是 API：
 * `GET https://github.com/linjianwuovo/andaxin/releases.atom` 回 10 条 `<entry>`，
 * `title` **是 release 的名字、不是 tag** —— tag 只能从 `<link rel="alternate">` 那条页面
 * 地址末尾取（10-25 凌晨踩过这个雷：拿 title 当 tag 拼出来的下载链和页面链都指向不存在的
 * 路径，一个下不动、一个 GitHub 回 404）。`updated` 是发布时间。
 * 为什么不用 `api.github.com`：**未认证限流一小时 60 次、按出口 IP 算** ——
 * 我拿代理那条线（公共出口 203.10.99.51）试，直接回 `API rate limit exceeded`；
 * 手机走共享出口或 VPN 节点时会经常撞这个 403。atom 那条没这个限，所以只用它。
 *
 * 代价说清楚：atom 里没有附件大小、没有 md5、没有正文 —— 这三样界面上就不显示，
 * 不拿"估计 24 MB"这种话糊人。附件直链按我们自己的命名规则拼
 * （`andaxin-<版本>-release.apk` / `andaxin-<版本>-beta.apk`，发版脚本就是这么命名的），
 * 同时把 release 页那条链接也给出去，不赌一条。
 */

/** 一条 release 里 atom 能给我们的那几样。 */
data class Release(
    val version: String,
    val tag: String,
    val date: String,
    /** 按命名规则拼出来的 apk 直链（这台机到得了 github.com 时才下得动）。 */
    val apkUrl: String,
    /** release 页，页里有真附件列表。 */
    val pageUrl: String,
)

/** 查的结果：这条线最新那版 + 它比手上这版新不新 + 什么时候查的。 */
data class UpdateCheck(
    val latest: Release,
    val newer: Boolean,
    val checkedAt: String,
)

class UpdateFeed {

    /**
     * @param current 本机版本名，形如 `0.8.27` 或 `0.8.27-beta`
     * @param beta 这台机器装的是不是 beta 那条线 —— 是就只看带 `-beta` 的 tag，
     *             不然正式版会一直提示"有新版"，而那个新版其实是调试线。
     */
    suspend fun check(current: String, beta: Boolean): Result<UpdateCheck> {
        val engine = createHttpEngine(HttpEngineConfig())
        val text = try {
            val resp = engine.execute(
                HttpCall(method = "GET", url = ATOM, headers = listOf("User-Agent" to UA)),
            )
            if (!resp.isSuccessful) {
                return Result.failure(
                    Exception(
                        if (resp.status == 404) "GitHub 上读不到这个仓的 release（仓刚转公开？）"
                        else "GitHub 回了 ${resp.status}",
                    ),
                )
            }
            resp.bodyText
        } catch (e: Exception) {
            return Result.failure(
                Exception("连不上 GitHub：" + (e.message ?: e::class.simpleName) + "（这台机现在到得了 github.com 吗）"),
            )
        }
        var best: Release? = null
        for (entry in ENTRIES.findAll(text)) {
            /*
             * atom 里 `<title>` 是 **release 的名字**（我发版时填的是 `0.8.30`），不是 tag。
             * tag 得从那条 `<link rel="alternate">` 的页面地址末尾取。
             * 以前这里拿 title 当 tag，于是两条链接都拼成 `/releases/tag/0.8.30-beta` ——
             * 真 tag 是 `v0.8.30-beta`，那一页根本不存在：所以「下载」每次都失败、
             * 「release 页」点进去是 GitHub 的 404（他 10-25 凌晨那两张截图）。
             */
            val page = ENTRY_LINK.find(entry.value)?.groupValues?.get(1)?.unescape().orEmpty()
            val tag = page.substringAfterLast('/').trim()
            if (tag.isBlank()) continue
            val isBeta = tag.endsWith("-beta")
            if (isBeta != beta) continue
            val version = plainOf(tag.removePrefix("v"))
            val r = Release(
                version = version,
                tag = tag,
                date = ENTRY_DATE.find(entry.value)?.groupValues?.get(1)?.substringBefore('T').orEmpty(),
                apkUrl = "$BASE/releases/download/$tag/" +
                    if (isBeta) "andaxin-$version-beta.apk" else "andaxin-$version-release.apk",
                pageUrl = page,
            )
            if (best == null || compareVersion(r.version, best!!.version) > 0) best = r
        }
        val found = best
            ?: return Result.failure(Exception("订阅里没找到这条线的 release（beta 线还没发过版？）"))
        return Result.success(UpdateCheck(found, versionNewer(found.version, current), now()))
    }

    /** `0.8.9` 要比 `0.8.27` 小 —— 按段比数字，不是比字符串。 */
    private fun compareVersion(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun now(): String = hhmm(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()))

    private companion object {
        const val BASE = "https://github.com/linjianwuovo/andaxin"
        const val ATOM = "$BASE/releases.atom"

        /** GitHub 不看 UA，但我们也不冒充学校的壳（那条 `okhttp/4.9.3` 是给校方那几发用的）。 */
        const val UA = "andaxin-update-check"
        val ENTRIES = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
        val ENTRY_LINK = Regex("<link[^>]*rel=\"alternate\"[^>]*href=\"([^\"]+)\"")
        val ENTRY_DATE = Regex("<updated>(.*?)</updated>")
    }
}

private fun hhmm(t: LocalDateTime): String =
    (if (t.monthNumber < 10) "0" else "") + t.monthNumber + "-" +
        (if (t.dayOfMonth < 10) "0" else "") + t.dayOfMonth + " " +
        (if (t.hour < 10) "0" else "") + t.hour + ":" +
        (if (t.minute < 10) "0" else "") + t.minute

/**
 * 订阅里那版比本机这版新吗。两边的 `-beta` 尾巴先抹掉 ——
 * 手上是 `0.8.29-beta` 而 beta 线最新也是 `0.8.29-beta` 时，那是"已是最新"，不是有新版。
 */
fun versionNewer(latest: String, current: String): Boolean {
    fun parts(v: String) = plainOf(v).split('.').map { it.toIntOrNull() ?: 0 }
    val x = parts(latest)
    val y = parts(current)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
        if (d != 0) return d > 0
    }
    return false
}

/** 版本名去掉 `-beta` 尾巴；界面上比"是不是同一版"用这个。 */
fun plainOf(v: String): String = v.removeSuffix("-beta").trim()

/** 上次查到的那条存本机（只存版本、tag、时间、两条链接 —— 不存任何凭据）。 */
object LastUpdate {
    const val KEY = "app.lastUpdate"

    fun toJson(r: Release, newer: Boolean, at: String): String = JSONObject()
        .put("version", r.version).put("tag", r.tag).put("date", r.date)
        .put("apkUrl", r.apkUrl).put("pageUrl", r.pageUrl)
        .put("newer", newer).put("at", at).toString()

    /** 回 (那条 release, 是不是新版, 什么时候查的)。 */
    fun fromJson(raw: String): Triple<Release, Boolean, String>? {
        if (raw.isBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return Triple(
            Release(
                version = o.optString("version"),
                tag = o.optString("tag"),
                date = o.optString("date"),
                apkUrl = o.optString("apkUrl"),
                pageUrl = o.optString("pageUrl"),
            ),
            o.optBoolean("newer"),
            o.optString("at"),
        )
    }
}

private fun String.unescape(): String =
    replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
