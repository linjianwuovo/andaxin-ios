package com.andaxin.app

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.andaxin.data.capture.AuthFeed
import com.andaxin.core.location.GpsFeed
import com.andaxin.core.poll.Poller
import com.andaxin.core.run.RunEngine
import com.andaxin.core.settings.AppearanceStore
import com.andaxin.core.store.SessionStore
import com.andaxin.core.store.Store
import com.andaxin.core.store.TokenKeepAlive
import com.andaxin.data.DashboardFeed
import com.andaxin.data.MessageFeed
import com.andaxin.data.NoticeFeed
import com.andaxin.data.SportsFeed
import com.andaxin.data.TimetableFeed
import com.andaxin.data.capture.BriefSwitch
import com.andaxin.data.capture.CaptureAffairDetail
import com.andaxin.data.capture.CaptureAffairs
import com.andaxin.data.capture.CaptureCreditDetail
import com.andaxin.data.capture.CaptureCreditList
import com.andaxin.data.capture.CaptureDorm
import com.andaxin.data.capture.CaptureDormDetail
import com.andaxin.data.capture.CaptureHandled
import com.andaxin.data.capture.CaptureHome
import com.andaxin.data.capture.CaptureIm
import com.andaxin.data.capture.CaptureMyAffairs
import com.andaxin.data.capture.CaptureExamDetail
import com.andaxin.data.capture.CaptureExamList
import com.andaxin.data.capture.CaptureHoliday
import com.andaxin.data.capture.CaptureHolidayDetail
import com.andaxin.data.capture.CaptureLabor
import com.andaxin.data.capture.CaptureLaborDetail
import com.andaxin.data.capture.CaptureLeaveForm
import com.andaxin.data.capture.CaptureSports
import com.andaxin.data.capture.YwlzTasks
import com.andaxin.data.brief.DetailSource
import com.andaxin.data.brief.FormSource
import com.andaxin.data.brief.ListSource
import com.andaxin.data.capture.CaptureMessages
import com.andaxin.data.capture.CaptureProfile
import com.andaxin.data.capture.CampusCardClient
import com.andaxin.data.capture.CaptureRepairDetail
import com.andaxin.data.capture.CaptureToRead
import com.andaxin.data.capture.CaptureRepairForm
import com.andaxin.data.capture.CaptureRepairList
import com.andaxin.data.capture.FormSwitch
import com.andaxin.data.capture.DetailSwitch
import com.andaxin.data.local.Affair
import com.andaxin.data.local.Credit
import com.andaxin.data.local.Dorm
import com.andaxin.data.local.Exam
import com.andaxin.data.local.Holiday
import com.andaxin.data.capture.CaptureNotices
import com.andaxin.data.capture.CaptureTimetable
import com.andaxin.data.capture.DashboardSource
import com.andaxin.data.capture.MessageSource
import com.andaxin.data.capture.NoticeSource
import com.andaxin.data.capture.School
import com.andaxin.data.capture.TimetableSource
import com.andaxin.data.local.LocalDashboard
import com.andaxin.data.local.LocalMessages
import com.andaxin.data.local.LocalNotices
import com.andaxin.data.local.LocalSports
import com.andaxin.data.local.LocalTimetable
import com.andaxin.data.local.Labor
import com.andaxin.data.local.Repair
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.andaxin.data.capture.CaptureDormBoard
import com.andaxin.data.capture.CaptureDormTaskDetail

/**
 * 手写的依赖图。全应用唯一允许 `new` 业务对象的地方。
 *
 * 不用 Dagger/Hilt 的理由是性能而不是口味：省掉一次启动期的组件初始化和 1~2MB 运行时，
 * 也不需要 KSP 代码生成。代价是加一个源要改这一个文件 —— 而它本来就该是唯一一处。
 *
 * 接一路真实报文，改的是下面那一组 `Source(capture, local, session)`：
 * 界面与 ViewModel 一行不动，因为它们只认 `data/Contract.kt` 那几个接口。
 */
class AppGraph(val context: Context) {

    val store: Store by lazy { Store(context) }

    /** 关于页那颗「查一次」：读我们自己那个 GitHub 仓的 release，不带任何凭据。 */
    val updates by lazy { com.andaxin.data.capture.UpdateFeed() }

    /** 明暗、底栏材质、四根玻璃滑杆 */
    val appearance: AppearanceStore by lazy { AppearanceStore(store) }

    /**
     * 调试面板。beta 那份给实现（假 GPS、模拟路线、关于页那一格），
     * 正式版那份是 null —— 实现类根本不在它的源集里，见 `core/debug/DebugKit.kt`。
     */
    val debug: com.andaxin.core.debug.DebugPanel? by lazy {
        com.andaxin.core.debug.DebugProviderFactory.create(store)
    }

    /** 登录与 token 刷新：这是全仓第一块接上真实报文的功能 */
    val auth: AuthFeed by lazy { AuthFeed() }

    /** 免责声明看过没有、登没登录、宿舍填没填 */
    val session: SessionStore by lazy { SessionStore(store) }

    /** token 到点前换成新的，换不动就请回登录页并带上原因 */
    /** 「记住密码」那把不可导出的 AES 密钥。构造失败也不该影响启动，所以是 lazy。 */
    val vault: com.andaxin.core.store.KeystoreVault by lazy {
        com.andaxin.core.store.KeystoreVault(context)
    }

    val keepAlive: TokenKeepAlive by lazy {
        TokenKeepAlive(
            auth, session,
            kickedDetector = school.kicked, kickedAck = { school.consumeKicked() },
            vault = vault,
        )
    }

    /** 校方接口的公共外壳：身份四件套、表单、JSON 钻取。所有 Capture* 都从这儿走。 */
    val school: School by lazy { School(session) }

    /**
     * 首页下半屏那四条壳接口 + 版本对照 + 实习生标记。
     * 字段是 10-20 晚把磁盘现存抓包按端点重扫、对着**响应真值**挑出来的
     * （`lxtmp/_shape2.md`），取证与那三个只有看真值才定得下来的判断写在 `CaptureHome` 顶上。
     */
    val home by lazy { CaptureHome(school, session) }

    /** 班级群与班内名单（`zhxy-new-scps/im/` 两条）。名单是别人的信息：只在本机看，不导出。 */
    val im by lazy { CaptureIm(school) }

    /**
     * 四路 feed 各自包一层开关：登录了走校方，没登录走占位（见 `data/capture/Sources.kt`）。
     * 已经接上真实报文的是消息、公告、全周课表、首页那几格、成绩、素质学分、待办事务、查寝。
     * 还欠的是本周跑量、报修、校园卡、AI 课堂、劳动教育、节假日离返校。
     */
    val timetable: TimetableFeed by lazy {
        TimetableSource(CaptureTimetable(school), LocalTimetable(), session)
    }

    val messages: MessageFeed by lazy {
        MessageSource(CaptureMessages(school, session), LocalMessages(), session)
    }

    val notices: NoticeFeed by lazy {
        NoticeSource(CaptureNotices(school), LocalNotices(), session)
    }

    val dashboard: DashboardFeed by lazy {
        // 未读数走 messages 这一路（跟消息屏同一个来源），不再用 notReadCount 那个 count —— 见 DashboardSource
        DashboardSource(
            school, session, LocalDashboard(messages, timetable), timetable, ywlz, messages, sports, dormBoard,
            readSnap = { com.andaxin.core.store.Snap.read(context, "home") },
            writeSnap = { com.andaxin.core.store.Snap.write(context, "home", it) },
        )
    }

    /** 成绩与学分：列表和详情共用一份进程内缓存（详情就是把列表那一行展开，校方没有单独接口） */
    private val examCapture by lazy { CaptureExamList(school) }
    private val creditCapture by lazy { CaptureCreditList(school) }

    val examList: ListSource by lazy { BriefSwitch(examCapture, Exam.list, session) }
    val examDetail: DetailSource by lazy { DetailSwitch(CaptureExamDetail(examCapture), Exam.detail, session) }
    val creditList: ListSource by lazy { BriefSwitch(creditCapture, Credit.list, session) }
    val creditDetail: DetailSource by lazy { DetailSwitch(CaptureCreditDetail(creditCapture), Credit.detail, session) }

    /**
     * 劳动教育五类工时 与 节假日离返校登记。
     * 两块以前是直接摆 `Labor.list` / `Holiday.list` 那两行编出来的假条目，
     * 现在走同一套开关：登录了只走校方，没登录才给占位（界面上那条说明也不许摘）。
     * 报文出处见 `CaptureStudent.kt` 顶上那段 —— 关键是那两条当年回"非法访问"
     * 不是报文错，是抓包抓在了会话最前面，10-20 拿活 token 原样重发全回 flag:true。
     */
    private val laborCapture by lazy { CaptureLabor(school) }

    val laborList: ListSource by lazy { BriefSwitch(laborCapture, Labor.list, session) }
    val laborDetail: DetailSource by lazy { DetailSwitch(CaptureLaborDetail(school), Labor.detail, session) }

    private val holidayCapture by lazy { CaptureHoliday(school, session) }

    val holidayList: ListSource by lazy { BriefSwitch(holidayCapture, Holiday.list, session) }
    val holidayDetail: DetailSource by lazy {
        DetailSwitch(CaptureHolidayDetail(holidayCapture), Holiday.detail, session)
    }

    /**
     * 业务流转（ywlz）那一套：待办事务、请假读链、销假、查寝。
     * 待办和查寝是**同一份数据**（查寝就是列表里 msgType=2 的那批），所以共用一个取数口，
     * 60 秒内不重复发；详情才按条目去拉审批链和表单回显。
     */
    val ywlz: YwlzTasks by lazy { YwlzTasks(school) }

    private val affairCapture by lazy { CaptureAffairs(ywlz) }
    private val dormCapture by lazy { CaptureDorm(ywlz) }

    val affairList: ListSource by lazy { BriefSwitch(affairCapture, Affair.list, session) }
    /**
     * 「我的申请」「已办」两栏。10-20 晚从抓包里把两条口的参数形状对出来了
     * （`lxtmp/_shape7.txt`），数组同样挂根节点 `rows`。
     * 没登录时走 [com.andaxin.data.local.NeedLoginList] —— 这两栏编假单据比空白更坏。
     */
    val myAffairList: ListSource by lazy {
        BriefSwitch(CaptureMyAffairs(ywlz), com.andaxin.data.local.NeedLoginList(), session)
    }
    val handledList: ListSource by lazy {
        BriefSwitch(CaptureHandled(ywlz), com.andaxin.data.local.NeedLoginList(), session)
    }
    /**
     * 「待阅」那一栏 = 待办同一条列表口、`approvedType=2`（10-13 从 H5 定死的 tab 号）。
     * 这一栏顶上多一颗「全部标已阅」，所以界面要问 `bulkLabel()` 有没有值再画键。
     */
    val toReadList: ListSource by lazy {
        BriefSwitch(CaptureToRead(school, ywlz), com.andaxin.data.local.NeedLoginList(), session)
    }
    val affairDetail: DetailSource by lazy {
        DetailSwitch(CaptureAffairDetail(school, ywlz, affairCapture), Affair.detail, session)
    }
    val dormList: ListSource by lazy { BriefSwitch(dormCapture, Dorm.list, session) }

    /**
     * 查寝那一屏新的数据源：月概览 + 月历 + 两节任务，全走 fdygl 直连。
     * 契约是 10-20 晚拿我们自己那把活 token 四发逐条现打验的（`CaptureDormBoard` 顶上那段）。
     * 老的 `dormList`（从 ywlz 待办里挑 msgType=2）留着给待办那一屏用，两条路不互相覆盖。
     */
    val dormBoard by lazy { CaptureDormBoard(school, session) }
    val dormTaskDetail: DetailSource by lazy { CaptureDormTaskDetail(school, dormBoard) }
    val dormDetail: DetailSource by lazy {
        DetailSwitch(CaptureDormDetail(school, ywlz, dormCapture), Dorm.detail, session)
    }

    /**
     * 发起请假那张表：10-20 现打接通了**读侧**（流程清单 → flowSheet 那个 JSON 串 → 递归收字段），
     * 取证见 `CaptureLeaveForm` 顶上那段。提交仍是死的，`submit()` 明确回失败。
     */
    val affairForm: FormSource by lazy { FormSwitch(CaptureLeaveForm(school, session), Affair.form, session) }

    /**
     * 报修。10-20 现打接上的：单据列表、单据详情、流程轨迹三条读口都在
     * `repair.aiit.edu.cn/bxjlSjd/` 那一族上，取证见 `CaptureRepair.kt` 顶上那段。
     * 新建那张单子的**提交**仍然没接（写口一次没发过），所以表单还是占位。
     */
    val repairList: ListSource by lazy { BriefSwitch(CaptureRepairList(school), Repair.list, session) }
    val repairDetail: DetailSource by lazy {
        DetailSwitch(CaptureRepairDetail(school), Repair.detail, session)
    }

    /** 新建报修那张表：下拉的可选值全部来自校方三条字典（见 `CaptureRepairForm` 顶上那段取证）。 */
    val repairForm: FormSource by lazy { FormSwitch(CaptureRepairForm(school, context), Repair.form, session) }

    /**
     * 校园卡（第三方新开普）。10-20 现打把三跳会话交换接通了，取证见 `CampusCardClient` 顶上那段。
     * 换出来的那把第三方 token 落在 `filesDir/ecard_token.txt` 复用 —— 每重换一次会踢掉上一把。
     */
    val card: CampusCardClient by lazy {
        CampusCardClient(school, session, File(context.filesDir, "ecard_token.txt"))
    }

    /**
     * AI 课堂（FIF 平台）。这一族跟 in.aiit 那套是**两套鉴权**：
     * 校内的 access_token 要先经一次单点登录换成 FIF 自己的 JWT，业务请求全走 header。
     * 见 `FifSession` 顶上那段取证 —— 本轮还没现打通过，等有机内会话补。
     */
    val fif: com.andaxin.data.capture.FifSession by lazy {
        com.andaxin.data.capture.FifSession(store, session)
    }

    val fifApi: com.andaxin.data.capture.FifApi by lazy { com.andaxin.data.capture.FifApi(fif) }

    /** 课程列表：走 FIF 自己的 myClassroom（抓包清点里是 A 级实样那条）。 */
    /**
     * 笔记站（FIF 的独立子域）。八条口 10-20 现打全通，取证见 `NoteApi` 顶上那段 ——
     * **`schoolId` 是隐式必填**，只发 `userId` 会静默回空数组。
     */
    val notes: com.andaxin.data.capture.NoteApi by lazy { com.andaxin.data.capture.NoteApi(fif) }

    val aiCourses: com.andaxin.data.capture.AiCourseFeed by lazy {
        com.andaxin.data.capture.AiCourseFeed(fif, fifApi)
    }

    /** 「我的」那一页的学院/专业/班级/辅导员 */
    val profile by lazy { CaptureProfile(school) }

    /**
     * 上面那两条口的结果缓存（学院/专业/班级 + 实习生标记）。
     * 挂在进程上而不是屏幕上，从二级页退回来时那一行才不会先跳回兜底文案。
     */
    val who by lazy { com.andaxin.core.store.WhoIs() }

    /**
     * 运动这一路：读侧走校方那台 sports:8082（总量/记录/维护申请三条已现打验过），
     * 上传那两条口没抓到实样，所以仍由 Local 那侧明确回失败。
     * 见 `CaptureSports` 顶上那段取证。
     */
    private val localSports by lazy {
        LocalSports(File(context.filesDir, "sport_routes.json"))
    }

    val sports: SportsFeed by lazy { CaptureSports(school, localSports) }

    /** 录播看到第几分（服务端那两条进度上报实测被拒，所以本机存，见 core/media/PlayProgress）。 */
    val playProgress by lazy { com.andaxin.core.media.PlayProgress(context.filesDir) }

    /** 消息轮询：应用活着的时候每五分钟拉一次 */
    val poller: Poller by lazy { Poller(messages, engineScope) }

    /** 真 GPS。beta 那份还接一路端内假 GPS（他自己写的那套），正式版这份 mock 是 null。 */
    val gps: GpsFeed by lazy { GpsFeed(context, debug?.mock) }

    /** 跑步引擎跟着进程活：界面切来切去不该打断采集 */
    val engine: RunEngine by lazy { RunEngine(engineScope) }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** 全应用共用这一份依赖图；没提供就直接报错，不给静默降级到空实现的机会。 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("还没在 AndaXinApp 里提供 AppGraph")
}
