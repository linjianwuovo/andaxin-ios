package com.andaxin.data.local

import com.andaxin.data.brief.BriefDetail
import com.andaxin.data.brief.BriefRow
import com.andaxin.data.brief.DetailSource
import com.andaxin.data.brief.Field
import com.andaxin.data.brief.Flag
import com.andaxin.data.brief.FormSpec
import com.andaxin.data.brief.FormSource
import com.andaxin.data.brief.ListSource

/*
 * 没抓包的那十几块，界面上先各挂一个占位 source。
 *
 * 每个都写了自己的取证状态 —— 这不是注释洁癖：哪块只有接口名、哪块连接口都没定，
 * 决定了他补抓包时先补哪一块，也决定了我能不能在那块上画"看起来对"的细节。
 */

private fun notWired(what: String): Result<String> =
    Result.failure(UnsupportedOperationException("$what 的报文还没接，这一笔交不出去"))

class PlaceholderList(private val rows: List<BriefRow>) : ListSource {
    override suspend fun rows() = Result.success(rows)
}

/**
 * 没有占位可给的那几栏用的。
 *
 * 「我的申请」「已办」这种，编两条假单据会让人以为自己有单子没办完 ——
 * 那比空白严重。所以没登录时明确回失败，界面上显示的是"还没登录"这句话。
 */
class NeedLoginList : ListSource {
    override suspend fun rows(): Result<List<BriefRow>> =
        Result.failure(Exception("还没登录：这一栏只列校方给的数据，我们一条都不编。"))
}

class PlaceholderDetail(
    private val title: String,
    private val fields: List<Pair<String, String>>,
    private val paragraphs: List<String> = listOf("正文段落要等接口接上才有；这里不放编出来的话。"),
) : DetailSource {
    override suspend fun detail(id: String) = Result.success(
        BriefDetail(title = title, fields = fields, paragraphs = paragraphs)
    )
}

class PlaceholderForm(private val spec: FormSpec) : FormSource {
    override suspend fun spec() = Result.success(spec.copy(sample = true))
    override suspend fun submit(values: Map<String, String>) = notWired(spec.title)
}

/** 查寝。【取证】只有 fdygl 那几个 .do 名，字段没实样。 */
object Dorm {
    val list = PlaceholderList(
        listOf(
            BriefRow("d1", "今晚 22:30 查寝", "3 号楼 412", "未开始", Flag.None),
            BriefRow("d2", "昨天 22:30 查寝", "本人已签到", "正常", Flag.Ok),
            BriefRow("d3", "10-12 查寝", "缺勤一次", "异常", Flag.Bad),
        )
    )
    val detail = PlaceholderDetail("查寝详情", listOf("日期" to "10-12", "楼栋" to "3 号楼 412", "结果" to "缺勤", "申诉期" to "3 天内"))
}

/** 考试成绩。【取证】只有 in.aiit 下的 .do 名。 */
object Exam {
    val list = PlaceholderList(
        listOf(
            BriefRow("e1", "高等数学", "王老师的班", "82"),
            BriefRow("e2", "大学英语", "Ms. Li", "76"),
            BriefRow("e3", "数据结构", "张老师", "缺考", Flag.Warn),
        )
    )
    val detail = PlaceholderDetail("成绩明细", listOf("课程" to "高等数学", "平时" to "—", "期中" to "—", "期末" to "—"))
}

/** 素质学分。【取证】只有 cqc 下的 .do 名。 */
object Credit {
    val list = PlaceholderList(
        listOf(
            BriefRow("c1", "德育", "要求 5", "3.0"),
            BriefRow("c2", "智育", "要求 10", "8.5"),
            BriefRow("c3", "体育", "要求 5", "5.0", Flag.Ok),
            BriefRow("c4", "劳育", "要求 5", "1.5", Flag.Warn),
        )
    )
    val detail = PlaceholderDetail("学分明细", listOf("类别" to "劳育", "已获" to "1.5", "要求" to "5.0", "认定时间" to "—"))
}

/** 劳动教育。【取证】只有 ldjy 下的 .do 名。 */
object Labor {
    val list = PlaceholderList(
        listOf(
            BriefRow("l1", "校园清扫", "已认定 2.0 小时", "通过", Flag.Ok),
            BriefRow("l2", "图书馆整理", "待认定", "审核中", Flag.Warn),
        )
    )
    val detail = PlaceholderDetail("工时明细", listOf("项目" to "校园清扫", "时长" to "2.0 小时", "认定状态" to "通过"))
}

/** 节假日离返校登记。【取证】接口名和字段都为零，这块最要先抓。 */
object Holiday {
    val list = PlaceholderList(
        listOf(
            BriefRow("h1", "国庆假期", "离校登记已提交", "已登记", Flag.Ok),
            BriefRow("h2", "中秋假期", "需在 9-15 前登记", "待登记", Flag.Warn),
        )
    )
    val detail = PlaceholderDetail("假期详情", listOf("假期" to "国庆", "开始" to "—", "结束" to "—"))
    val form = PlaceholderForm(
        FormSpec(
            "离返校登记",
            listOf(
                Field.Date("leaveDate", "离校日期"),
                Field.Date("backDate", "返校日期"),
                Field.Choice("destination", "去向", options = listOf("回家", "出游", "留校", "实习")),
                Field.Text("note", "备注", hint = "选填"),
            ),
        )
    )
}

/** 报修。【取证】抓包痕迹厚（3 处抓包、14 个 .do），是这几块里最接近能直接接的。 */
object Repair {
    val list = PlaceholderList(
        listOf(
            BriefRow("r1", "水龙头漏水", "3 号楼 412 · 报修单 10-14", "处理中", Flag.Warn),
            BriefRow("r2", "空调不制冷", "3 号楼 412 · 报修单 09-28", "已完成", Flag.Ok),
        )
    )
    val detail = PlaceholderDetail(
        "报修单 10-14",
        listOf("类型" to "水暖", "报修时间" to "10-14 19:02", "状态" to "处理中", "维修工" to "—"),
        paragraphs = listOf("师傅已接单，预计 24 小时内上门。"),
    )
    val form = PlaceholderForm(
        FormSpec(
            "新建报修",
            listOf(
                Field.Choice("type", "故障类型", options = listOf("水暖", "电", "家具", "网络", "空调")),
                Field.Person("dorm", "宿舍"),
                Field.Text("detail", "问题描述", hint = "越具体越好，比如哪个龙头、什么时候开始"),
            ),
        )
    )
}

/** 待办事务 / 请假。【取证】抓包 2 处、.do 23 处，表单字段本来是从 getFlowSheet.do 动态来的。 */
object Affair {
    val list = PlaceholderList(
        listOf(
            BriefRow("a1", "请假申请 · 事假 2 天", "辅导员审批中", "审批中", Flag.Warn),
            BriefRow("a2", "销假 · 已返校", "10-08 提交", "通过", Flag.Ok),
            BriefRow("a3", "待办通知 · 青年大学习", "本周内", "未阅", Flag.Unread),
        )
    )
    val detail = PlaceholderDetail("请假单", listOf("类型" to "事假", "起止" to "—", "审批人" to "辅导员", "当前状态" to "审批中"))
    val form = PlaceholderForm(
        FormSpec(
            "发起请假",
            listOf(
                Field.Choice("kind", "请假类型", options = listOf("事假", "病假", "公假")),
                Field.Date("from", "开始"),
                Field.Date("to", "结束"),
                Field.Text("reason", "事由"),
            ),
        )
    )
    // 真实表单是服务端给的，字段随学校改流程而变，所以这里写死的一版只是形状占位
    val dynamicForm = true
}

/* AI 课堂不再用占位数据：那一屏改成真去问 FIF（feature/aiclass/AiClassScreens.kt）。
   以前这四行假条目里连"随堂投票"都编出来了，而 FIF 根本没有投票接口。 */
