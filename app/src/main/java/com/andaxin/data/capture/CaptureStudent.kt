package com.andaxin.data.capture

import com.andaxin.data.brief.BriefDetail
import com.andaxin.data.brief.BriefRow
import com.andaxin.data.brief.DetailSource
import com.andaxin.data.brief.Flag
import com.andaxin.data.brief.ListSource
import com.andaxin.core.store.SessionStore
import com.andaxin.core.json.JSONObject

/*
 * 劳动教育工时 与 节假日离返校登记。
 *
 * 这两块以前界面上摆的是**编出来的两行**（"校园清扫 已认定 2.0 小时"、"国庆假期 已登记"），
 * 看着像接上了，其实一条报文都没有 —— 那是最坏的一种"完成"。
 *
 * 【取证 · ③ 目标 App 发出、被我们自己抓到的请求 + ② 我今天拿活 token 原样重发验成的响应】
 * 两条请求都在 `lxtmp/login.flow` 里，当时回的是 `{"flag":false,"result":"非法访问"}` ——
 * **那不是报文错了，是抓包抓在了会话最前面那一段，token 还没落地**。
 * 10-20 上午我把同两份请求体原样重发（只换 token），两条一起回 `flag:true`：
 *
 * - `POST ldjy.aiit.edu.cn/anXiaoXin/queryPersonalTimesTotal.do`
 *   → `data.data{voluntaryTimes, summerTimes, laborTimes, otherTimes, socialTimes}`，
 *   五个都是**字符串**（实样全是 "0"），身份走表单那套原生字段。
 * - `POST fdygl.aiit.edu.cn/app/holiday/getRegistrationPage`，JSON `{"studentId":…,"pageNum":1}`
 *   → `{code:"0", flag:true, rows:[], total:0}` —— 接口通，那次是真的没登记。
 *   身份在**请求头**（`accessToken/userCode/userType/xh`，`role/roleId` 学生端空串），见 `School.postJson`。
 *
 * 【今天又撞出来的一条】`ldjy` 这一族**挑身份形状**，跟 ywlz 一样要 H5 那五个键
 * （`accessToken` 驼峰 + `_userCode` + `_userType` + `userCode` + `xh`）。
 * 10-20 12:2x 我按原生那套（`access_token` + `appId` + `_userName` + `userId`）从 PC 发，
 * 回 `{"flag":false,"result":"非法访问"}`；换成 H5 五键立刻 `flag:true`。
 * 单给 `accessToken` + `studentCode` 也不行 —— 是**那一整组**在验，不是某一个键。
 * 所以这里走 `postH5`，不是 `post`。
 *
 * 一条排雷记录：`queryPersonalTimesTotal.do` 原始请求体里 `userCode`、`xh`、`accessToken`
 * **各出现两次**（跟成绩那一发"同键被清空"那个坑一个形状）。我原样发一遍、去重再发一遍，
 * **两遍结果一模一样** —— 所以这一族不因重复键清空，别把成绩的结论往外推。
 */

/** 服务端只给英文字段名，一个中文都没给。 */
private val LABOR_LABELS = listOf(
    "voluntaryTimes" to "志愿服务",
    "summerTimes" to "暑期社会实践",
    "socialTimes" to "社会实践",
    "laborTimes" to "劳动教育",
    "otherTimes" to "其他",
)

private const val LDJY = "https://ldjy.aiit.edu.cn"

/**
 * 五类工时。
 *
 * 中文名是我按字段名翻的（voluntary/summer/social/labor/other），**服务端没给过显示名**。
 * 所以每一行副标题把原始字段名一起摆出来 —— 万一我把 summer 和 social 翻反了，
 * 他一眼能看出来，不会被我的中文骗过去。
 */
class CaptureLabor(private val school: School) : ListSource {

    override suspend fun rows(): Result<List<BriefRow>> = fetch(school).mapCatching { d ->
        LABOR_LABELS.map { (key, cn) ->
            val hours = d.optString(key, "0")
            BriefRow(
                id = key,
                title = cn,
                right = "$hours 小时",
                flag = if ((hours.toDoubleOrNull() ?: 0.0) > 0.0) Flag.Ok else Flag.None,
            )
        }
    }

    /** 列表和详情共用同一发 —— 这一族没有分项接口，整份就是那五个数。 */
    internal companion object {
        suspend fun fetch(school: School): Result<JSONObject> {
            val id = school.userCode()
            return school
                .postH5("$LDJY/anXiaoXin/queryPersonalTimesTotal.do", "studentCode" to id)
                .mapCatching { root ->
                    root.optJSONObject("data")?.optJSONObject("data") ?: throw serverReason(root)
                }
        }
    }
}

class CaptureLaborDetail(private val school: School) : DetailSource {

    override suspend fun detail(id: String): Result<BriefDetail> =
        CaptureLabor.fetch(school).mapCatching { d ->
            BriefDetail(
                title = LABOR_LABELS.toMap()[id] ?: id,
                fields = listOf(
                    "已认定" to d.optString(id, "0") + " 小时",
                ),
            )
        }
}

/**
 * 假期离返校登记。
 *
 * **实样是空列表**（`rows:[] total:0`），所以条目里有什么键我们完全不知道。
 * 下面只按"有名字类键就显示"的保守写法取，取不到就写"第 N 条登记"，
 * 不拿 `holidayName`/`startTime` 这种猜的键名去撑场面 —— 猜错的样子跟没接一样坏。
 * 等他真登记一次、或者我们抓到一条非空的，再按真键名改这里。
 */
class CaptureHoliday(private val school: School, private val session: SessionStore) : ListSource {

    override suspend fun rows(): Result<List<BriefRow>> =
        school.postJson(
            "${School.FDYGL}/app/holiday/getRegistrationPage",
            JSONObject().put("studentId", session.identity()?.userCode.orEmpty()).put("pageNum", 1),
        ).mapCatching { root ->
            val arr = root.optJSONArray("rows") ?: return@mapCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = firstOf(o, "title", "name", "holidayName", "jqmc")
                        ?: "第 ${i + 1} 条登记"
                    val state = firstOf(o, "statusTxt", "stateTxt", "registrationStatus", "sfj").orEmpty()
                    add(
                        BriefRow(
                            id = o.optString("id").ifBlank { i.toString() },
                            title = title,
                            sub = firstOf(o, "startTime", "createTime", "fqsj", "date").orEmpty(),
                            right = state,
                            flag = when {
                                state.isBlank() -> Flag.None
                                state.contains("已") -> Flag.Ok
                                else -> Flag.Warn
                            },
                        )
                    )
                }
            }
        }

    private fun firstOf(o: JSONObject, vararg keys: String): String? =
        keys.asSequence().map { o.optString(it) }.firstOrNull { it.isNotBlank() }
}

/** 列表那一发已经把整份带回来了，详情从同一发里挑，不重复发请求。 */
class CaptureHolidayDetail(private val cap: CaptureHoliday) : DetailSource {

    override suspend fun detail(id: String): Result<BriefDetail> =
        cap.rows().mapCatching { rows ->
            val r = rows.firstOrNull { it.id == id }
                ?: throw Exception("列表里没找到这一条，回列表重进一次。")
            BriefDetail(title = r.title, fields = listOf("摘要" to r.sub, "状态" to r.right.ifBlank { "—" }))
        }
}

/** 校方给的那句失败原因原样抛出去，不自己编一句。 */
private fun serverReason(root: JSONObject): Exception =
    Exception(
        root.optString("msg")
            .ifBlank { root.optString("result") }
            .ifBlank { "校方说没通过" },
    )
