package com.andaxin.data.brief

import androidx.compose.runtime.Immutable

/*
 * 一批"列表 / 详情 / 表单"形状的通用契约。
 *
 * 为什么要这一层：没抓包的那十几块（查寝、成绩、学分、劳动、节假日、报修、待办事务、AI 课堂）
 * 界面形状其实是一样的 —— 一行有标题副标题和一个状态，点进去是若干键值对加几段正文，
 * 要填的就是一组字段。先把形状定下来，每块只写一个 source，
 * 他补完抓包只换 source 的实现，界面一行不动。
 *
 * 每个 source 顶上那行【取证】是本仓的规矩：写清字段有没有实样顶着。
 */

enum class Flag { None, Ok, Bad, Warn, Unread }

@Immutable
data class BriefRow(
    val id: String,
    val title: String,
    val sub: String = "",
    val right: String = "",
    val flag: Flag = Flag.None,
)

@Immutable
data class BriefDetail(
    val title: String,
    val fields: List<Pair<String, String>> = emptyList(),
    val paragraphs: List<String> = emptyList(),
    /** 审批链那种一环接一环的东西：界面上画成带圆点的竖线，不再是一段"1. … 2. …"的文字。 */
    val steps: List<Step> = emptyList(),
    /**
     * 这一条上校方允许做的写动作，界面上一个动作一颗键，**每颗都要连按两次**。
     *
     * 以前这里是单个 `writable` + `writeLabel`（只有销假用），所以"这一环到底有几个按钮"在界面上
     * 表达不出来。现在把 [Action] 列表交出去，来源是 `process/getButtons.do` 给的按钮（有实样那条）
     * 加上条目自己带的标记（`isWithdraw`、待销假那一环）。
     */
    val actions: List<Action> = emptyList(),
)

/**
 * 详情里的一颗写操作键。[key] 是给 [DetailSource.act] 认的暗号，[label] 是给人看的话，
 * 而且 **label 用校方给的原文**（`getButtons` 里那条 `label`），我们只在这张表里把它对上口。
 */
@Immutable
data class Action(
    val key: String,
    val label: String,
    /** 红色那颗：删掉、驳回这种一旦发出去就退不回来的 */
    val danger: Boolean = false,
    /** 按下去之前要先问的几个问题（比如报修的"确认并评价"）。空 = 直接连按两次就发。 */
    val prompt: Prompt? = null,
)

/**
 * 一颗写操作键要收的答案。界面上按 [groups] 的顺序画，回给 [DetailSource.act] 的是 `group.key -> 选中的原文`。
 * 选项文字就是发出去的顺序索引由数据源自己翻 —— 界面只负责把选中的那条交回去。
 */
@Immutable
data class Prompt(
    val title: String,
    val groups: List<PromptGroup>,
    val note: String = "",
)

@Immutable
data class PromptGroup(
    val key: String,
    val label: String,
    /** 单选的几个选项；[freeText] 为真时这个列表可以空着 */
    val options: List<String> = emptyList(),
    val freeText: Boolean = false,
    val optional: Boolean = false,
    /** 这一组只有当选了某个值才要填（评价只在"已完成"时选） */
    val onlyWhen: Pair<String, String>? = null,
)

/** 审批链的一环。四个字段全是校方给的原文，界面不加工。 */
data class Step(
    val who: String,
    val what: String,
    val at: String = "",
    val head: String = "",
)

@Immutable
sealed class Field {
    abstract val key: String
    abstract val label: String

    data class Text(override val key: String, override val label: String, val hint: String = "") : Field()
    /**
     * 只读的一条：校方表单里那种"说明文字"，以及我们这台渲染不了的控件。
     * 单独立一档是因为它们以前都借 [Text] 显示 —— 一个能输入的框摆在"说明1"下面，
     * 里面还写着"（这一条是说明文字）"，看着像没填完，其实根本没有可填的东西。
     */
    data class Note(override val key: String, override val label: String, val text: String = "") : Field()
    data class Number(override val key: String, override val label: String, val unit: String = "") : Field()
    data class Choice(override val key: String, override val label: String, val options: List<String>) : Field()
    data class Date(override val key: String, override val label: String) : Field()
    data class Person(override val key: String, override val label: String) : Field()
    /**
     * 照片那一格（现在只有报修在用）。值存的是**本机选中的 content uri**，逗号分隔。
     *
     * 这里存的不是图本身，也不是已经传上去的文件 id —— 传图是往学校写东西，
     * 那一发排在最后那颗「提交」后面，由人按。选图、删图这一格都不碰网络。
     */
    data class Photos(
        override val key: String,
        override val label: String,
        val max: Int = 10,
    ) : Field()
}

@Immutable
/**
 * [sample] = 这是没登录时那份形状占位的表：字段是编的，提交也是发不出去的。
 * [defaults] = 键（= 校方的 `model`）→ 该预先填进去的值。发起流程那张表里"学号/姓名/班级"这一类
 *   是学校自己给的预填值（`findDefinitionBase.do`），而且**提交时要照发**，
 *   所以必须由数据源给、不能只当占位文字看。
 * [footnote] = 这张表末尾的一句说明（比如"附件那一格本机没做"），不给就不摆。
 * [canSubmit] = 这台机器上到底发不发得出去。发不出去（那条写口还没接）就别摆那颗键 ——
 *   摆一颗按下去只会报错的主按钮，比少一颗按钮坏得多。
 */
data class FormSpec(
    val title: String,
    val fields: List<Field>,
    val sample: Boolean = false,
    val defaults: Map<String, String> = emptyMap(),
    val footnote: String? = null,
    val canSubmit: Boolean = true,
)

interface ListSource {
    suspend fun rows(): Result<List<BriefRow>>

    /**
     * 这一栏顶部那颗**批量写操作**键的名字；`null` = 这一栏没有批量动作。
     * 现在只有「待阅」那一栏有（全部标已阅）。界面画它之前先问这个，别摆一颗按下去没反应的。
     */
    fun bulkLabel(): String? = null

    /** 批量那颗键按下去要发的那一发。停在最后一步由人按、连按两次、不自动重试 —— 和详情里那几颗同一档规矩。 */
    suspend fun bulk(): Result<String> = Result.failure(Exception("这一栏没有批量操作"))
}

interface DetailSource {
    suspend fun detail(id: String): Result<BriefDetail>

    /**
     * 详情里某一颗写操作键。[action] 是 [Action.key]，[answers] 是那颗键上 [Action.prompt] 收的答案
     * （键 = `PromptGroup.key`，值 = 选中的那一条**原文**）；界面已经连按两次确认过才会走到这儿。
     * 默认不给：没接上报文的一律回 failure，界面就只显示那句原因，绝不静默成功。
     */
    suspend fun act(id: String, action: String, answers: Map<String, String> = emptyMap()): Result<String> =
        Result.failure(Exception("这一屏没有可发的写操作"))
}

interface FormSource {
    suspend fun spec(): Result<FormSpec>

    /** 提交。没接上报文时一律回 failure，不许静默成功。 */
    suspend fun submit(values: Map<String, String>): Result<String>
}
