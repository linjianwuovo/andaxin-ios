package com.andaxin.data.capture

import com.andaxin.core.store.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 首页那四条"壳接口"与版本对照。
 *
 * 这一族 10-19 那批抓包里全都有实样，但**只有形状没有真值**，所以这次（10-20 晚）把磁盘上
 * 现存的 15 个抓包文件重新按端点过了一遍，把每一条的响应真值抄出来对字段
 * （`lxtmp/_shape2.md`、`_shape5.md`、`_shape6.txt`）。下面每个函数注释里"真值"就是指那一份。
 *
 * 三个只有看真值才能定下来的判断：
 * ① 服务清单里"名字"是 `mc` 不是 `name`/`fwdx` —— 39 个键里 30 个是 null，
 *    `name` 实测就是 null，只有 `mc` 有字（样例「第二课堂」）；
 * ② `sjdfwdz` 给的是**完整 https 直链**（`https://dekt.aiit.edu.cn/secondClass-app/#/portal/index`），
 *    不是相对路径，所以能直接喂 WebView；但抓包里**没有任何一条 H5 的 GET 在 URL 上带过 token**
 *    （全量扫过：带 token 的 GET 只有 17wanxiao 那三跳、valid.do、aiitpass、sports 的 getServerTime），
 *    所以"官方是怎么把登录态带进这一页的"我们仍然没有证据 —— 界面上明说可能要再登一次；
 * ③ checkVersion 那条，安小信自己发的 `currentVersion` 是**内部号 79**，不是 `V2.0.5`。
 *    这条决定我们怎么发：报上去的是"我们抄协议照的那一版"（79），
 *    服务端回 `news:true` 就等于"官方已经比我们照的那版新了"，这才是这句话对我们有用的意思。
 *    我们自己的 versionCode（1xx 那一套）跟它不是一个体系，绝不报上去 —— 报了 `news` 就永远是猜的。
 */

/** 首页服务清单上的一格。`targetUrl` 空 = 校方这条没给跳转地址，界面上就不该点。 */
data class SchoolService(
    val id: String,
    val name: String,
    val iconUrl: String,
    val targetUrl: String,
    val online: Boolean,
)

/** 「特殊服务」实测是四条联系方式：`description` 是人看的名字，`infoName` 装的却是电话号码。 */
data class SpecialContact(
    val code: String,
    val label: String,
    val contact: String,
)

/**
 * 校方的版本通道。七个键全是真值抄下来的：
 * `banben`=内部号(79) `bb`=V2.0.5 `gxrz`=更新日志 `gxlx`=更新类型 `xt`=系统 `news`=有没有新版 `fileUrl`=apk 直链。
 */
data class OfficialVersion(
    val code: String,
    val name: String,
    val note: String,
    val kind: String,
    val hasNew: Boolean,
    val apkUrl: String,
)

/** 首页一次拉齐的那一坨。哪一路挂了都不该把整屏拖成错误态，所以各自带 why。 */
data class HomeExtras(
    val services: List<SchoolService> = emptyList(),
    val contacts: List<SpecialContact> = emptyList(),
    val calendar: String = "",
    val why: List<String> = emptyList(),
)

class CaptureHome(private val school: School, private val session: SessionStore) {

    /**
     * 首页服务清单。真值：`data[]` **7 条**，每条 39 键里只有
     * `id`(11 位数字串) `mc` `sjdfwdz` `tpUrl` `onlineFlag` `sfysfw` `guideFlag` `version` 有值，
     * 其余全 null。`onlineFlag=="1"` 是在线，`guideFlag=="0"`。
     */
    suspend fun services(): Result<List<SchoolService>> =
        school.post("$SCPS/appService/getNewAppServiceHome.do").mapCatching { root ->
            arrOf(root.opt("data")).map { o ->
                SchoolService(
                    id = o.optString("id"),
                    name = o.optString("mc").ifBlank { o.optString("name") },
                    iconUrl = o.optString("tpUrl"),
                    targetUrl = o.optString("sjdfwdz"),
                    online = o.optString("onlineFlag") == "1",
                )
            }.filter { it.name.isNotBlank() }
        }

    /** 联系方式四条。`infoCode` 是分档号（真值 "1"），`infoName` 里装的是电话号码。 */
    suspend fun contacts(): Result<List<SpecialContact>> =
        school.post("$SCPS/appService/getSpecialService.do").mapCatching { root ->
            arrOf(root.opt("data")).map { o ->
                SpecialContact(
                    code = o.optString("infoCode"),
                    label = o.optString("description"),
                    contact = o.optString("infoName"),
                )
            }.filter { it.label.isNotBlank() || it.contact.isNotBlank() }
        }

    /**
     * 校方给安小信自己用的版本通道。见文件头第 ③ 条：报上去的 `currentVersion` 是**我们抄协议照的那一版**，
     * 不是我们 App 自己的 versionCode。
     */
    suspend fun officialVersion(reference: String): Result<OfficialVersion> =
        school.post(
            "$SCPS/appInfo/checkVersion.do",
            "currentVersion" to reference,
            "system" to "1",
        ).mapCatching { root ->
            val d = root.optJSONObject("data") ?: return@mapCatching OfficialVersion("", "", "", "", false, "")
            OfficialVersion(
                code = d.optString("banben"),
                name = d.optString("bb"),
                note = d.optString("gxrz"),
                kind = d.optString("gxlx"),
                hasNew = d.optBoolean("news", false),
                apkUrl = d.optString("fileUrl"),
            )
        }

    /**
     * 是不是实习生。真值：`data{isInternStu:false}` —— 一个布尔，服务端没给别的口径。
     * 这一发只在登录了才有意义（身份七件套是必带的）。
     */
    suspend fun intern(): Result<Boolean> =
        school.post("$SCPS/personal/checkIsInternStu.do").mapCatching {
            it.optJSONObject("data")?.optBoolean("isInternStu", false) ?: false
        }

    /**
     * 校历：学年 / 学期 / 现在第几周。
     *
     * 【取证】`POST zhxy-csh/dict/selectZcList.do` 的 `data{}` 直接给
     * `week:"8" schoolTerm:"1" schoolYear:"2026"`（课表那一发用的就是这条，见 `CaptureTimetable`）。
     * 另一条 `zhxy-csh/mobile/getXlVo.do` 回 `data.xlVo{xn,xq,zc}`，但现存抓包里那一次 **`zc` 是空串**
     * —— 周次给不出来，所以这里用 selectZcList，不用 getXlVo。
     */
    suspend fun calendar(): Result<String> =
        school.post("$CSH/dict/selectZcList.do").mapCatching { root ->
            val d = root.optJSONObject("data") ?: JSONObject()
            val year = d.optString("schoolYear")
            val term = d.optString("schoolTerm")
            val week = d.optString("week")
            if (year.isBlank()) "" else "${year} 学年 · 第 $term 学期 · 第 $week 周"
        }

    /**
     * 首页一次出齐：四路并发，谁挂了都不影响别人，挂的原因收进 `why`。
     * 没登录就整块不给（这些接口每条都要身份七件套）。
     */
    suspend fun load(): Result<HomeExtras> {
        if (!session.signedIn()) return Result.failure(Exception("还没登录，首页这些清单要校方数据"))
        return coroutineScope {
            val svc = async { services() }
            val con = async { contacts() }
            val cal = async { calendar() }
            val why = mutableListOf<String>()
            fun <T> take(r: Result<T>, what: String, fallback: T): T =
                r.getOrElse { why += "${what}没拉到：" + (it.message ?: "不知道"); fallback }
            Result.success(
                HomeExtras(
                    services = take(svc.await(), "服务清单", emptyList()),
                    contacts = take(con.await(), "联系方式", emptyList()),
                    calendar = take(cal.await(), "校历", ""),
                    why = why,
                )
            )
        }
    }

    /** 这一族的数组直接挂 `data`，没有分页壳；给的是对象就退回 `data.data`，两种都吃。 */
    private fun arrOf(v: Any?): List<JSONObject> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> {
            val a = v.optJSONArray("data") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        else -> emptyList()
    }

    private companion object {
        const val SCPS = School.SCPS
        const val CSH = School.CSH
    }
}
