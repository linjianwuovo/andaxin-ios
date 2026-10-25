package com.andaxin.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.andaxin.R
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Motion
import com.andaxin.core.settings.Appearance
import com.andaxin.core.settings.spec
import com.andaxin.feature.about.AboutScreen
import com.andaxin.feature.aiclass.CourseDetailScreen
import com.andaxin.feature.aiclass.RecordPlayScreen
import com.andaxin.feature.aiclass.ScanScreen
import com.andaxin.feature.aiclass.CoursewareScreen
import com.andaxin.feature.aiclass.QrSignScreen
import com.andaxin.feature.home.HomeScreen
import com.andaxin.feature.me.MeScreen
import com.andaxin.feature.modules.OnboardingScreen
import com.andaxin.feature.message.MessageScreen
import com.andaxin.feature.notice.NoticeDetailScreen
import com.andaxin.feature.notice.NoticeScreen
import com.andaxin.feature.schedule.TimetableScreen
import com.andaxin.feature.sport.SportDetailScreen
import com.andaxin.feature.sport.SportMaintainScreen
import com.andaxin.feature.sport.SportRecordsScreen
import com.andaxin.feature.sport.SportRunScreen
import com.andaxin.feature.modules.AffairDetailScreen
import com.andaxin.feature.modules.AffairFormScreen
import com.andaxin.feature.modules.AffairTabsScreen
import com.andaxin.feature.aiclass.AiClassListScreen
import com.andaxin.feature.aiclass.OpenCourseDetailScreen
import com.andaxin.feature.aiclass.OpenCourseListScreen
import com.andaxin.feature.modules.CampusCardScreen
import com.andaxin.feature.modules.GroupScreen
import com.andaxin.feature.modules.CardFlowsScreen
import com.andaxin.feature.modules.CreditDetailScreen
import com.andaxin.feature.modules.CreditListScreen
import com.andaxin.feature.modules.DormDetailScreen
import com.andaxin.feature.modules.ExamDetailScreen
import com.andaxin.feature.modules.ExamListScreen
import com.andaxin.feature.modules.HolidayDetailScreen
import com.andaxin.feature.modules.HolidayFormScreen
import com.andaxin.feature.modules.HolidayListScreen
import com.andaxin.feature.modules.LaborDetailScreen
import com.andaxin.feature.modules.LaborListScreen
import com.andaxin.feature.me.ProfileScreen
import com.andaxin.feature.modules.LoginDeps
import com.andaxin.feature.modules.LoginScreen
import com.andaxin.feature.modules.MoreScreen
import com.andaxin.feature.notify.NotifyScreen
import com.andaxin.feature.modules.RepairDetailScreen
import com.andaxin.feature.modules.RepairFormScreen
import com.andaxin.feature.modules.RepairListScreen
import com.andaxin.feature.sport.SportStartScreen
import com.andaxin.feature.theme.ThemeScreen
import com.andaxin.feature.web.WebScreen
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andaxin.feature.modules.DormBoardScreen
import com.andaxin.feature.modules.DormTaskScreen
import com.andaxin.feature.brief.BriefDetailPage

/**
 * 底栏五格。格数与顺序是他定的，不许增删改序 —— 要动先问。
 * 每格一个图标 + 一行字，图标是自己画的 vector，不引 material-icons-extended 那个大包。
 */
private enum class Shelf(
    val labelRes: Int,
    /** 未选中：主笔那层（跟着墨色） */
    val iconRes: Int,
    /** 未选中：点睛那层（跟着强调色）。拆两张是因为色得跟着主题和自定义强调色走 */
    val iconAccentRes: Int,
    /** 选中：整枚实心双色填充，只用强调色上一色 */
    val iconOnRes: Int,
) {
    Home(R.string.tab_home, R.drawable.ic_tab_home, R.drawable.ic_tab_home_accent, R.drawable.ic_tab_home_on),
    Timetable(R.string.tab_schedule, R.drawable.ic_tab_schedule, R.drawable.ic_tab_schedule_accent, R.drawable.ic_tab_schedule_on),
    Message(R.string.tab_message, R.drawable.ic_tab_message, R.drawable.ic_tab_message_accent, R.drawable.ic_tab_message_on),
    Notice(R.string.tab_notice, R.drawable.ic_tab_notice, R.drawable.ic_tab_notice_accent, R.drawable.ic_tab_notice_on),
    Me(R.string.tab_me, R.drawable.ic_tab_me, R.drawable.ic_tab_me_accent, R.drawable.ic_tab_me_on),
}

object Route {
    const val SHELF = "shelf"
    const val THEME = "theme"
    const val ABOUT = "about"
    /** 个人信息：头像、学籍资料、退出登录（从「我的」头像点进来）。 */
    const val PROFILE = "profile"
    const val SPORT = "sport"
    const val SPORT_RUN = "sport/run"
    const val SPORT_RECORDS = "sport/records"
    const val SPORT_MAINTAIN = "sport/maintain"
    const val SPORT_PLANNER = "sport/planner"
    const val SPORT_DETAIL = "sport/detail/{id}"

    const val WELCOME = "gate"
    const val MORE = "more"
    const val NOTIFY = "notify"
    const val CARD = "card"
    const val CARD_FLOWS = "card/flows"
    const val LOGIN = "login"
    const val DORM = "dorm"
    const val DORM_DETAIL = "dorm/detail/{id}"
    const val DORM_TASK = "dorm/task/{id}"
    const val EXAM = "exam"
    const val EXAM_DETAIL = "exam/detail/{id}"
    const val CREDIT = "credit"
    const val CREDIT_DETAIL = "credit/detail/{id}"
    const val LABOR = "labor"
    const val LABOR_DETAIL = "labor/detail/{id}"
    const val HOLIDAY = "holiday"
    const val HOLIDAY_DETAIL = "holiday/detail/{id}"
    const val HOLIDAY_FORM = "holiday/form"
    const val REPAIR = "repair"
    const val REPAIR_DETAIL = "repair/detail/{id}"
    const val REPAIR_FORM = "repair/form"
    const val AFFAIR = "affair"
    /** 首页那四个数各进一栏：`affair/tab/1`（0 待办 / 1 待阅 / 2 我的申请 / 3 已办）。 */
    const val AFFAIR_TAB = "affair/tab/{tab}"
    const val AFFAIR_DETAIL = "affair/detail/{id}"
    const val AFFAIR_FORM = "affair/form"
    /** 第二课堂（dekt 那一族，只读：学分总览 + 我的申报）。 */
    const val DEKT = "dekt"
    const val AICLASS = "aiclass"
    const val AICLASS_DETAIL = "aiclass/detail/{id}"
    const val AICLASS_SCAN = "aiclass/scan"

    /** 扫码签到：把码里的链接交给带 FIF 会话的 WebView 打开。{u} 是整条 URL。 */
    const val AICLASS_SIGN_QR = "aiclass/signqr/{u}"
    /** 课件预览：一页一张图。 */
    const val AICLASS_PAPER = "aiclass/paper/{r}/{c}/{t}?title={title}"
    const val AICLASS_PLAY = "aiclass/play/{id}"
    const val OPEN_COURSE = "opencourse"
    const val OPEN_COURSE_DETAIL = "opencourse/detail/{id}"
    const val GROUP = "group"
    /** 公告详情：{id} 进来时已 Uri.encode 过（校方那批 id 里带斜杠）。 */
    const val NOTICE_DETAIL = "notice/detail/{id}"

    /**
     * 自带 WebView。参数是 **URL-safe Base64**（无填充）后的地址：
     * 校方那些直链里 `?`、`&`、`#` 全都会出现（`…#/portal/index` 就是井号路由），
     * 原串塞进导航路径会把整条路由匹配切坏。编解码都在 `feature/web/WebScreen.kt` 里。
     */
    const val WEB = "web/{u}"
}

/** 首页「待办事务」那四个数各进哪一栏（0 待办 / 1 待阅 / 2 我的申请 / 3 已办）。 */
fun affairTab(index: Int): String = "affair/tab/${index.coerceIn(0, 3)}"

/** 详情路由都带一个 {id}，注册时统一走这个，省掉二十行重复。 */
private const val ID_ARG = "id"

private const val TAB_ARG = "tab"

/** 首页数字格进哪一栏，参数就是个 0..3 的序号。 */
private fun tabPattern() = androidx.navigation.navArgument(TAB_ARG) { defaultValue = "0" }

private fun idPattern() = androidx.navigation.navArgument(ID_ARG) { defaultValue = "" }

/**
 * 每一条路由都从这儿注册，因为转场只有一种：**新一屏整页盖进来，底下那一屏让位并压暗**。
 *
 * 往前 [Motion.pageEnter]（280ms，ease-out：冲到位再收），往回 [Motion.pageExit]（220ms）——
 * 返回这一下用户早知道自己要看什么，快一格才贴手。被盖住的那一半走 ease-in-out，
 * 它不该比进场的那一半抢眼。
 *
 * 10-22 改的：以前两页各走满一屏（进 {it} / 出 {-it}），那是"平移"，看着像两张纸对换，
 * 不是"这一屏把上一屏推开"。现在出去的只后退 [PAGE_PARALLAX] 并压暗到 [PAGE_DIM]，
 * 进来的从右边整页盖过去 —— 他要的"二级覆盖一级推入"。
 * 后退不是位移为零：留一点视差，盖住的那一屏才在动、才有前后两层。
 *
 * 正因为两层会**同时露在屏幕上**，每一屏自己必须是不透明的：底下那层 NavHost 的底色
 * 在所有页之后，挡不住重叠区的穿帮。所以内容统一包一层同色底。
 */
private const val PAGE_PARALLAX = 0.25f
private const val PAGE_DIM = 0.72f

private fun NavGraphBuilder.page(
    name: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(
        route = name,
        arguments = arguments,
        enterTransition = { slideInHorizontally(tween(Motion.pageEnter, easing = Motion.EaseOut)) { it } },
        exitTransition = {
            slideOutHorizontally(tween(Motion.pageEnter, easing = Motion.EaseInOut)) {
                (-it * PAGE_PARALLAX).toInt()
            } + fadeOut(tween(Motion.pageEnter), targetAlpha = PAGE_DIM)
        },
        popEnterTransition = {
            slideInHorizontally(tween(Motion.pageExit, easing = Motion.EaseOut)) {
                (-it * PAGE_PARALLAX).toInt()
            } + fadeIn(tween(Motion.pageExit))
        },
        popExitTransition = { slideOutHorizontally(tween(Motion.pageExit, easing = Motion.EaseInOut)) { it } },
        content = { entry ->
            val scope = this
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                with(scope) { content(entry) }
            }
        },
    )
}

@Composable
fun RootShell() {
    val graph = LocalAppGraph.current
    val appearance by graph.appearance.state
        .collectAsStateWithLifecycle(initialValue = Appearance())
    val nav = rememberNavController()

    // 底栏和整屏共用同一份 Appearance：明暗在最外层（MainActivity）决定
    NavHost(
        navController = nav,
        startDestination = Route.SHELF,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        page(Route.SHELF) { Gate(appearance, open = { nav.navigate(it) }) }
        page(Route.THEME) { ThemeScreen(onBack = { nav.popBackStack() }) }
        page(Route.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() }, open = { nav.navigate(it) })
        }
        page(Route.PROFILE) { ProfileScreen(onBack = { nav.popBackStack() }) }
        page(Route.SPORT) { SportStartScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.SPORT_RUN) { SportRunScreen(onBack = { nav.popBackStack() }) }
        page(Route.SPORT_RECORDS) {
            SportRecordsScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() })
        }
        page(Route.SPORT_MAINTAIN) { SportMaintainScreen(onBack = { nav.popBackStack() }) }
        // 只有高级模式里的「新建路线」会导航到这里；普通界面没有入口
        // 画模拟线那一屏只有 beta 那份注册得进来：正式版 debug 是 null，这条路由压根没挂
        graph.debug?.let { panel ->
            page(Route.SPORT_PLANNER) { panel.planner.invoke { nav.popBackStack() } }
        }
        page(
            Route.SPORT_DETAIL,
            arguments = listOf(idPattern()),
        ) { entry ->
            SportDetailScreen(recordId = entry.arguments?.getString(ID_ARG).orEmpty(), onBack = { nav.popBackStack() })
        }

        page(Route.MORE) { MoreScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.NOTIFY) { NotifyScreen(onBack = { nav.popBackStack() }) }
        page(Route.CARD) { CampusCardScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.CARD_FLOWS) { CardFlowsScreen(onBack = { nav.popBackStack() }) }
        page(Route.LOGIN) {
            LoginScreen(
                // 登录屏进共享层了，graph 的四路依赖在这里明着递（见 shared 的 feature/modules/Login.kt）
                LoginDeps(
                    auth = graph.auth,
                    session = graph.session,
                    vault = graph.vault,
                    kicked = graph.keepAlive.kicked,
                    consumeKicked = graph.keepAlive::consumeKicked,
                ),
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }

        page(Route.DORM) { DormBoardScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.DORM_TASK, arguments = listOf(idPattern())) { e ->
            DormTaskScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.DORM_DETAIL, arguments = listOf(idPattern())) { e ->
            DormDetailScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.EXAM) { ExamListScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.EXAM_DETAIL, arguments = listOf(idPattern())) { e ->
            ExamDetailScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.CREDIT) { CreditListScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.CREDIT_DETAIL, arguments = listOf(idPattern())) { e ->
            CreditDetailScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.DEKT) { com.andaxin.feature.modules.SecondClassScreen(onBack = { nav.popBackStack() }) }
        page(Route.LABOR) { LaborListScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.LABOR_DETAIL, arguments = listOf(idPattern())) { e ->
            LaborDetailScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.HOLIDAY) { HolidayListScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.HOLIDAY_DETAIL, arguments = listOf(idPattern())) { e ->
            HolidayDetailScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.HOLIDAY_FORM) { HolidayFormScreen(onBack = { nav.popBackStack() }) }
        page(Route.REPAIR) { RepairListScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.REPAIR_DETAIL, arguments = listOf(idPattern())) { e ->
            RepairDetailScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.REPAIR_FORM) { RepairFormScreen(onBack = { nav.popBackStack() }) }
        page(Route.AFFAIR) { AffairTabsScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(Route.AFFAIR_TAB, arguments = listOf(tabPattern())) { e ->
            AffairTabsScreen(
                startTab = e.arguments?.getString(TAB_ARG)?.toIntOrNull() ?: 0,
                open = { nav.navigate(it) },
                onBack = { nav.popBackStack() },
            )
        }
        page(Route.AFFAIR_DETAIL, arguments = listOf(idPattern())) { e ->
            AffairDetailScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.AFFAIR_FORM) { AffairFormScreen(onBack = { nav.popBackStack() }) }
        page(Route.NOTICE_DETAIL, arguments = listOf(idPattern())) { e ->
            NoticeDetailScreen(e.arguments?.getString(ID_ARG).orEmpty()) { nav.popBackStack() }
        }
        page(Route.AICLASS) { AiClassListScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
        page(
            Route.AICLASS_SIGN_QR,
            arguments = listOf(androidx.navigation.navArgument("u") { defaultValue = "" }),
        ) { e ->
            QrSignScreen(
                url = e.arguments?.getString("u").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        page(
            Route.AICLASS_PAPER,
            arguments = listOf(
                androidx.navigation.navArgument("r") { defaultValue = "" },
                androidx.navigation.navArgument("c") { defaultValue = "" },
                androidx.navigation.navArgument("t") { defaultValue = "" },
                androidx.navigation.navArgument("title") { defaultValue = "" },
            ),
        ) { e ->
            CoursewareScreen(
                resourceId = e.arguments?.getString("r").orEmpty(),
                coursewareId = e.arguments?.getString("c").orEmpty(),
                teachClassId = e.arguments?.getString("t").orEmpty(),
                title = e.arguments?.getString("title").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        page(Route.AICLASS_SCAN) {
            ScanScreen(
                onBack = { nav.popBackStack() },
                onSignUrl = { url ->
                    nav.navigate("aiclass/signqr/" + android.net.Uri.encode(url))
                },
            )
        }
        page(Route.AICLASS_DETAIL, arguments = listOf(idPattern())) { e ->
            CourseDetailScreen(e.arguments?.getString(ID_ARG).orEmpty(), open = { nav.navigate(it) }) { nav.popBackStack() }
        }
        page(Route.AICLASS_PLAY, arguments = listOf(idPattern())) { e ->
            RecordPlayScreen(e.arguments?.getString(ID_ARG).orEmpty(), onBack = { nav.popBackStack() })
        }
        page(Route.OPEN_COURSE) {
            OpenCourseListScreen(open = { nav.navigate(it) }, onBack = { nav.popBackStack() })
        }
        page(Route.OPEN_COURSE_DETAIL, arguments = listOf(idPattern())) { e ->
            OpenCourseDetailScreen(e.arguments?.getString(ID_ARG).orEmpty(), onBack = { nav.popBackStack() })
        }
        page(Route.GROUP) { GroupScreen(onBack = { nav.popBackStack() }) }
        page(
            Route.WEB,
            arguments = listOf(androidx.navigation.navArgument("u") { defaultValue = "" }),
        ) { e ->
            WebScreen(e.arguments?.getString("u").orEmpty(), "", onBack = { nav.popBackStack() })
        }
    }
}

/**
 * 第一屏该看到什么，由会话决定，不靠改 startDestination：
 * 改 startDestination 会让 NavHost 在标志位翻转时整个重建，页面栈直接丢。
 */
@Composable
private fun Gate(appearance: Appearance, open: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val session by graph.session.state.collectAsStateWithLifecycle(com.andaxin.core.store.Session())
    val scope = rememberCoroutineScope()

    // 光看 loggedIn 触发不了"进程一直活着、token 半路过期"那一种，所以每次回到前台再查一次。
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watch = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++
        }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch) }
    }
    LaunchedEffect(session.loggedIn, resumed) {
        if (session.loggedIn) graph.keepAlive.ensureFresh()
    }

    /*
     * 后台预热（登录着才有意义）：
     * ① AI 课堂要 fif.ensure 的 SSO + 学期 + 课程三发，冷启动时全摊在"点进 AI 课堂"那一刻；
     * ② 校园卡要 hub → redirect → authorize 三跳换会话；
     * ③ 课表/首页各走各的，这里不碰。
     * 全都 fire-and-forget，失败不弹任何东西 —— 用户没点那一屏之前，这些失败不归他管。
     */
    LaunchedEffect(session.loggedIn) {
        if (session.loggedIn) {
            launch { runCatching { graph.fif.ensure() } }
            launch { runCatching { graph.aiCourses.courses() } }
            launch { runCatching { graph.card.warm() } }
        }
    }

    /**
     * 会话半路没了（被顶号 / 校方夜里清了会话），别等下一次回前台才处理。
     *
     * 任何一屏先撞到"没有访问权限"或"非法访问"，`School` 就把标志立起来；这里立刻过一遍闸门，
     * 让它清会话并把原因送到登录页那一句上。不这么做的话用户会停在原地：
     * 一块屏写着"登录已在别处被顶掉，重新登一次"，可界面上没有任何一条路能去登录。
     */
    val sessionKicked by graph.school.kicked.collectAsStateWithLifecycle(false)
    LaunchedEffect(sessionKicked) {
        if (sessionKicked && session.loggedIn) graph.keepAlive.ensureFresh()
    }

    when {
        !session.onboarded -> OnboardingScreen(onAgree = { scope.launch { graph.session.agreeTerms() } })
        !session.loggedIn && !session.browsing -> LoginScreen(
            LoginDeps(
                auth = graph.auth,
                session = graph.session,
                vault = graph.vault,
                kicked = graph.keepAlive.kicked,
                consumeKicked = graph.keepAlive::consumeKicked,
            ),
            onDone = { open(Route.SHELF) },
            onBack = { scope.launch { graph.session.browseWithoutLogin() } },
        )
        else -> ShelfHost(appearance, open)
    }
}

@Composable
private fun ShelfHost(appearance: Appearance, open: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = Shelf.Home.ordinal) { Shelf.entries.size }
    val scope = rememberCoroutineScope()
    val haze = remember { HazeState() }
    val labels = Shelf.entries.map { androidx.compose.ui.res.stringResource(it.labelRes) }
    val icons = Shelf.entries.map { it.iconRes }
    val iconsAccent = Shelf.entries.map { it.iconAccentRes }
    val iconsOn = Shelf.entries.map { it.iconOnRes }
    // 五格里只有「消息」那一格有未读概念（公告那份数据里没有已读未读），顺序对齐 Shelf
    val unreadMsgs by com.andaxin.core.store.Unread.messages.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                /*
                 * 这里以前写着 `.padding(bottom = 92.dp)`，把整页挡在底栏上方。
                 * 那一挡就是"玻璃栏背景是纯色"的真正原因：栏子采的那一层背景底下永远只有
                 * 一片空窗口底色，模糊再大糊出来的也是一个颜色，跟不透明没区别。
                 * 现在整页铺到屏幕底，内容从栏底下滚过去，折射和模糊才有东西可吃；
                 * 各屏尾部那条 ShelfTail 负责让最后一条停在栏子上沿之上。
                 */
                .hazeSource(haze),
        ) { index ->
            when (Shelf.entries[index]) {
                Shelf.Home -> HomeScreen(open = open)
                Shelf.Timetable -> TimetableScreen()
                Shelf.Message -> MessageScreen()
                Shelf.Notice -> NoticeScreen(open = open)
                Shelf.Me -> MeScreen(open = open)
            }
        }

        ShelfBar(
            current = pager.currentPage,
            pageCount = Shelf.entries.size,
            onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            material = appearance.material,
            spec = appearance.spec(),
            hazeState = haze,
            labels = labels,
            icons = icons,
            iconsAccent = iconsAccent,
            iconsOn = iconsOn,
            badges = listOf(0, 0, unreadMsgs, 0, 0),
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
    }
}
