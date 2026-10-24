package com.andaxin.data.capture

import com.andaxin.core.store.SessionStore
import com.andaxin.data.brief.BriefDetail
import com.andaxin.data.brief.BriefRow
import com.andaxin.data.brief.DetailSource
import com.andaxin.data.brief.Flag
import com.andaxin.data.brief.ListSource
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 成绩与素质学分：这两块的实样是 10-19 深夜那一轮抓包里现打的，字段照实样来。
 * 详情没有单独的接口（校方列表一次给全字段），所以详情就是把列表那一行展开，
 * 不另外发请求 —— 也就不用编一个不存在的 detail 接口。
 */

/**
 * 考试成绩。
 *
 * 【取证】两条，都是 POST、form 表单：
 * ① `zhxy-csh/mobile/mobileXsxkKkxns.do`，**空请求体**，根节点 `rows:[{text:"2026-2027",value:"2026"},…]`
 *    —— 这是学年选择器，`value` 就是下面那条要的 `kkxn`。
 * ② `zhxy-csh/mobile/stuExamScore.do`，参数 `kkxn=2026&kkxq=1&userCode=<学号>`，
 *    根节点 `rows[]`，单条 `{kcmc 课名, cj 成绩, xf 学分, rkjs 任课教师, kclb 课程类别,
 *    kkbm 开课部门, ksxz 考试性质(正考/…), kcdm 课程代码}`。
 *
 * 两个坑：① `rows` 挂在根节点，不在 `data` 里，跟 scps 那一族不一样；
 * ② 学期号 `kkxq` 校方没给选择器，我拿 `dict/selectZcList.do` 的 `schoolTerm` 顶上，
 *    这个替换是推断不是实样，界面上不写"第几学期"字样，免得说错。
 *
 * 还有个更阴的：这条接口的表单里 `userCode` **只能出现一次**。10-19 23:05 拿设备上那把活 token
 * 逐个字段试过 —— 身份那七个字段随便加都不影响，只有重复的 `userCode` 会让服务端回
 * `flag:true` + `rows:[]`（不报错，静默清空）。所以公共外壳里做了同键去重，别在这儿再手加一遍。
 */
class CaptureExamList(private val school: School) : ListSource {

    /** 详情要展开同一行，列表拉完先存这儿（进程内，不落地）。 */
    internal val cache = HashMap<String, BriefDetail>()

    override suspend fun rows(): Result<List<BriefRow>> {
        val years = school.post("${School.CSH}/mobile/mobileXsxkKkxns.do")
            .mapCatching { it.optJSONArray("rows") ?: JSONArray() }
        if (years.isFailure) return Result.failure(years.exceptionOrNull()!!)
        val y = years.getOrThrow().optJSONObject(0) ?: return Result.failure(Exception("学年表是空的"))
        val xn = y.optString("value")

        val term = school.post("${School.CSH}/dict/selectZcList.do")
            .mapCatching { it.optJSONObject("data")?.optString("schoolTerm").orEmpty() }
            .getOrDefault("1")

        // 只看"当前这一桶"：学年取学年表第一条，学期取周次表给的那个。
        // 不往前翻旧学年 —— 翻出来的是去年的成绩，界面上又没地方标是哪一学年，
        // 那等于拿旧数据冒充这学期。空着就空着。
        return school.post(
            "${School.CSH}/mobile/stuExamScore.do",
            "kkxn" to xn,
            "kkxq" to term,
            "userCode" to school.userCode(),
        ).mapCatching { root ->
            val arr = root.optJSONArray("rows") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("kcmc")
                    if (name.isBlank()) continue
                    val score = o.optString("cj")
                    val key = o.optString("kcdm").ifBlank { name } + "|" + o.optString("ksxz")
                    cache[key] = BriefDetail(
                        title = name,
                        fields = listOf(
                            "成绩" to score.ifBlank { "—" },
                            "学分" to o.optString("xf"),
                            "任课教师" to o.optString("rkjs").ifBlank { "—" },
                            "课程类别" to o.optString("kclb"),
                            "开课部门" to o.optString("kkbm"),
                            "考试性质" to o.optString("ksxz"),
                            "课程代码" to o.optString("kcdm"),
                        ),
                    )
                    add(
                        BriefRow(
                            id = key,
                            title = name,
                            sub = o.optString("rkjs").ifBlank { o.optString("kclb") },
                            right = score.ifBlank { "—" },
                            flag = if (score.isBlank()) Flag.Warn else Flag.None,
                        )
                    )
                }
            }
        }
    }
}

class CaptureExamDetail(private val list: CaptureExamList) : DetailSource {
    override suspend fun detail(id: String): Result<BriefDetail> =
        list.cache[id]?.let { Result.success(it) }
            ?: Result.failure(Exception("这一门还没拉下来，回列表重进一次"))
}

/**
 * 素质学分。
 *
 * 【取证】`POST cqc.aiit.edu.cn/creditMobile/findPersonCredit.do`。
 * 这条的身份字段比别处多一倍：`access_token`、`token`、`accessToken` 三个名字都要带，
 * 学号也要 `xh`/`studentCode`/`userCode`/`_userCode` 四份 —— 实样就是这么发的，别省。
 * 响应在 `data.personnelCreditVo`：`countCredit`(str)、`pass`(bool)、
 * `list[] = {name 模块名, type 模块A…, credit 已获}`。
 */
class CaptureCreditList(private val school: School) : ListSource {

    internal val cache = HashMap<String, BriefDetail>()

    override suspend fun rows(): Result<List<BriefRow>> {
        val id = school.userCode()
        val extra = listOf(
            "token" to school.token(),
            "accessToken" to school.token(),
            "xh" to id,
            "studentCode" to id,
        )
        return school
            .post("https://cqc.aiit.edu.cn/creditMobile/findPersonCredit.do", *extra.toTypedArray())
            .mapCatching { root ->
                val vo = root.optJSONObject("data")?.optJSONObject("personnelCreditVo") ?: JSONObject()
                val arr = vo.optJSONArray("list") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = o.optString("name")
                        if (name.isBlank()) continue
                        val key = name + "|" + o.optString("type")
                        cache[key] = BriefDetail(
                            title = name,
                            fields = listOf(
                                "模块" to o.optString("type"),
                                "已获学分" to o.optString("credit"),
                                "总学分" to vo.optString("countCredit"),
                                "是否达标" to if (vo.optBoolean("pass")) "是" else "否",
                            ),
                        )
                        add(
                            BriefRow(
                                id = key,
                                title = name,
                                sub = o.optString("type"),
                                right = o.optString("credit"),
                            )
                        )
                    }
                }
            }
    }
}

class CaptureCreditDetail(private val list: CaptureCreditList) : DetailSource {
    override suspend fun detail(id: String): Result<BriefDetail> =
        list.cache[id]?.let { Result.success(it) }
            ?: Result.failure(Exception("这一项还没拉下来，回列表重进一次"))
}

/**
 * 「我的」那一页的身份信息。
 *
 * 【取证】`zhxy-new-scps/personal/queryStudentStatus.do`：`data` 直接是对象，
 * `{yxmc 学院, zymc 专业, bjmc 班级, xz 学制, xjzt 学籍状态, fdy 辅导员, lxfs 辅导员电话,
 * rxny 入学年月, rxnj, xh, xm, xb, mz, …}`。
 * `personal/getPersonalInfo.do`：`data{nickname, mobilePhone, fileAddress, sex}`。
 * 两条都只读，不碰写接口。
 */
class CaptureProfile(private val school: School) {

    suspend fun load(): Result<Profile> {
        val status = school.post("${School.SCPS}/personal/queryStudentStatus.do")
            .mapCatching { it.optJSONObject("data") ?: JSONObject() }
        if (status.isFailure) return Result.failure(status.exceptionOrNull()!!)
        val s = status.getOrThrow()
        val me = school.post("${School.SCPS}/personal/getPersonalInfo.do")
            .mapCatching { it.optJSONObject("data") ?: JSONObject() }
            .getOrDefault(JSONObject())
        return Result.success(
            Profile(
                college = s.optString("yxmc"),
                major = s.optString("zymc"),
                className = s.optString("bjmc"),
                length = s.optString("xz"),
                status = s.optString("xjzt"),
                counselor = s.optString("fdy"),
                counselorPhone = s.optString("lxfs"),
                enrolledAt = s.optString("rxny"),
                nickname = me.optString("nickname"),
                mobile = me.optString("mobilePhone"),
            )
        )
    }
}

data class Profile(
    val college: String,
    val major: String,
    val className: String,
    val length: String,
    val status: String,
    val counselor: String,
    val counselorPhone: String,
    val enrolledAt: String,
    val nickname: String,
    val mobile: String,
)

/** 登录了走真数据，没登录走占位 —— 和课表消息那四路一个规矩。 */
class BriefSwitch(
    private val capture: ListSource,
    private val local: ListSource,
    private val session: SessionStore,
) : ListSource {
    override suspend fun rows(): Result<List<BriefRow>> =
        if (session.signedIn()) capture.rows() else local.rows()

    // 批量那颗键不给会话闸门：没登录时这一栏的 rows() 本来就失败、`byId` 是空的，
    // 真接口那侧自己会回 null（`signedIn()` 是 suspend，不能在这种给界面问的同步口里调）
    override fun bulkLabel(): String? = capture.bulkLabel() ?: local.bulkLabel()

    override suspend fun bulk(): Result<String> = capture.bulk()
}

class DetailSwitch(
    private val capture: DetailSource,
    private val local: DetailSource,
    private val session: SessionStore,
) : DetailSource {
    override suspend fun detail(id: String): Result<BriefDetail> =
        if (session.signedIn()) capture.detail(id) else local.detail(id)

    /** 写操作只走真接口那一侧；占位那侧本来就回"没接上报文"，不兜底。 */
    override suspend fun act(id: String, action: String, answers: Map<String, String>): Result<String> =
        capture.act(id, action, answers)
}
