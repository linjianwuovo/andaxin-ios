package com.andaxin.shared

import com.andaxin.core.store.Store
import com.andaxin.core.store.SessionStore
import com.andaxin.core.store.createVault
import com.andaxin.data.capture.AuthFeed
import com.andaxin.data.capture.CaptureDormBoard
import com.andaxin.data.capture.CaptureHome
import com.andaxin.data.capture.CaptureMessages
import com.andaxin.data.capture.CaptureNotices
import com.andaxin.data.capture.CaptureTimetable
import com.andaxin.data.capture.DashboardSource
import com.andaxin.data.capture.MessageSource
import com.andaxin.data.capture.NoticeSource
import com.andaxin.data.capture.School
import com.andaxin.data.SportsFeed
import com.andaxin.data.capture.TimetableSource
import com.andaxin.data.capture.YwlzTasks
import com.andaxin.data.local.LocalDashboard
import com.andaxin.data.local.LocalMessages
import com.andaxin.data.local.LocalNotices
import com.andaxin.data.local.LocalTimetable
import com.andaxin.data.DashboardFeed
import com.andaxin.data.MessageFeed
import com.andaxin.data.NoticeFeed
import com.andaxin.data.TimetableFeed
import com.andaxin.state.MessageModel
import com.andaxin.state.HomeModel
import com.andaxin.state.NoticeModel
import com.andaxin.state.TimetableModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iOS 侧的装配图（AppGraph 的 iOS 分身）。
 *
 * iOS 壳（Swift）只跟这一个类打交道：拿 Deps、拿屏幕工厂。装配顺序照 app 的 AppGraph：
 * Store → Session → School → capture 实例 → Source 开关（登录走校方、未登录走占位）→ Model。
 * 路径约定：DataStore 文件放 <Documents>/andaxin.preferences_pb（Android 是 filesDir 那份，两平台各存各的）。
 */
class IosAppGraph(documentsPath: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val store: Store = Store(documentsPath + "/andaxin.preferences_pb")
    val session: SessionStore = SessionStore(store)
    val vault = createVault()
    val auth: AuthFeed = AuthFeed()
    val school: School = School(session)

    // 数据源开关（照 AppGraph 的 Source(capture, local, session) 三件套）
    private val localTimetable = LocalTimetable()
    private val localMessages = LocalMessages()
    private val localNotices = LocalNotices()

    val timetableFeed: TimetableFeed = TimetableSource(CaptureTimetable(school), localTimetable, session)
    val messageFeed: MessageFeed = MessageSource(CaptureMessages(school, session), localMessages, session)
    val noticeFeed: NoticeFeed = NoticeSource(CaptureNotices(school), localNotices, session)

    private val ywlz = YwlzTasks(school)
    private val dormBoard = CaptureDormBoard(school, session)
    private val sportsFeed: SportsFeed = com.andaxin.data.capture.CaptureSports(
        school,
        com.andaxin.data.local.LocalSports(null),
    )

    val dashboardFeed: DashboardFeed = DashboardSource(
        school, session,
        LocalDashboard(messageFeed, timetableFeed),
        timetableFeed, ywlz, messageFeed, sportsFeed, dormBoard,
    )

    // 五个 tab 的 Model（宿主负责建和活，屏幕只吃 Deps）
    val messageModel = MessageModel(messageFeed)
    val homeModel = HomeModel(dashboardFeed, timetableFeed)
    val noticeModel = NoticeModel(noticeFeed)
    val timetableModel = TimetableModel(timetableFeed)

    /**
     * 给 Swift 的协程启动口：block 在主线程协程里跑。suspend 的细节（async/throws 过桥）
     * 全留在 Kotlin 侧消化，Swift 只拿同步回调——Swift 闭包里不许出现 await/try。
     */
    fun launchMain(block: () -> Unit) {
        scope.launch { block() }
    }

    /**
     * 启动期的 Gate：挂起查完登录态，回主线程把结果告诉 Swift（isSignedIn 是普通 Boolean）。
     * Swift 侧一个 await/try 都不用写。
     */
    fun resolveGate(onResolved: (isSignedIn: Boolean) -> Unit) {
        scope.launch {
            val signed = session.signedIn()
            onResolved(signed)
        }
    }
}
