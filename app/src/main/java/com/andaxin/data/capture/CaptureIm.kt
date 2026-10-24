package com.andaxin.data.capture

import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 班级群与班内名单（`zhxy-new-scps/im/` 那一族）。
 *
 * 【取证 · ③ 真 App 发出、被我们抓到的报文】10-20 把磁盘现存抓包按端点重扫了一遍
 * （`lxtmp/_shape2.md`），两条都有实样：
 * - `POST im/myGroupChat.do` → `data[]` 一条群分类：
 *   `{groupName, groupCount:int, groupType:"1", differGroupVos:[{groupId(32 位大写十六进制), groupName, groupType, imGroupId:null}]}`；
 * - `POST im/mailListGroupHomePage.do` → `data[]` 一个班：
 *   `{id(=上面那个 groupId), name, code(10 位), peopleNum:int, type:"1",
 *    mailListGroupShowVoList:[{userCode, userType, xm, xb, tx}]}`。
 * 两条的请求侧除了身份七件套**一个业务参数都没多带**（真值扫过 23 次，形状只有一种）。
 *
 * 有一件事只有看真值才发现，而且直接决定界面怎么写：
 * **名单条目里 `xb` 装的不是性别，是学院名。** 键名按拼音是"性别"，内容是"某某学院"，
 * 服务端就这么错着给的。所以界面上那一栏的标签写"学院"，注释里留这句话，
 * 别下一个改这屏的人看见 `xb` 就去标"性别"。
 * `tx`（头像）实测全 null，所以这一族没有头像可用。
 *
 * 名单是**别人**的姓名和学号：只在界面上看，不写进仓、不进日志、不做导出。
 */

/** 群里的一名成员。`college` 来自服务端那个名叫 `xb` 的键，见文件头。 */
data class ClassMate(
    val userCode: String,
    val name: String,
    val college: String,
    val avatarUrl: String,
)

/** 一个班（校方在这条接口上把它当成一个"邮件组"）。 */
data class ClassGroup(
    val id: String,
    val name: String,
    val code: String,
    val memberCount: Int,
    val members: List<ClassMate>,
)

/** 群分类：真值里只有一类「班级群」，下面挂着 `differGroupVos` 那些具体的班。 */
data class GroupKind(
    val name: String,
    val count: Int,
    val groups: List<GroupId>,
)

data class GroupId(val id: String, val name: String)

class CaptureIm(private val school: School) {

    /**
     * 我有哪些群。`data` 直接是数组（实测 1 条），每条的 `differGroupVos` 才是具体班。
     * 这一发的用处是拿到 `groupId` 与班名；名单在 [roster]。
     */
    suspend fun kinds(): Result<List<GroupKind>> =
        school.post("$SCPS/im/myGroupChat.do").mapCatching { root ->
            arr(root.opt("data")).map { o ->
                GroupKind(
                    name = o.optString("groupName"),
                    count = o.optInt("groupCount"),
                    groups = arr(o.opt("differGroupVos")).map { g ->
                        GroupId(g.optString("groupId"), g.optString("groupName"))
                    },
                )
            }
        }

    /**
     * 班内名单。`data[]` 每一项是一个班，成员挂 `mailListGroupShowVoList`。
     * 没给参数 —— 也就是**服务端按会话认班**，所以这一发只能在登录后发，换班号没用。
     */
    suspend fun roster(): Result<List<ClassGroup>> =
        school.post("$SCPS/im/mailListGroupHomePage.do").mapCatching { root ->
            arr(root.opt("data")).map { o ->
                ClassGroup(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    code = o.optString("code"),
                    memberCount = o.optInt("peopleNum"),
                    members = arr(o.opt("mailListGroupShowVoList")).map { m ->
                        ClassMate(
                            userCode = m.optString("userCode"),
                            name = m.optString("xm"),
                            college = m.optString("xb"),
                            avatarUrl = m.optString("tx"),
                        )
                    },
                )
            }
        }

    private fun arr(v: Any?): List<JSONObject> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> {
            val a = v.optJSONArray("data") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        else -> emptyList()
    }

    private companion object {
        const val SCPS = School.SCPS
    }
}
