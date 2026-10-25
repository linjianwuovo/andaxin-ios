package com.andaxin.core.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/*
 * 桌面那枚图标可以换 —— 五套「尖顶 A」配色，出厂立着的是第一枚（蓝底青杠）。
 *
 * 做法是 Android 那套 activity-alias：`AndroidManifest.xml` 里给每一枚图标挂一条 alias，
 * 都指向 `.MainActivity`，**只有选中的那条是 enabled**。换图标 = 开一条、关一条，
 * MainActivity 本身一直开着，所以不会把正在跑的 App 关掉（`DONT_KILL_APP`）。
 *
 * 两个坑，写在这免得下次踩：
 * ① 默认那枚也必须挂在 alias 上。要是 MainActivity 自己带着 LAUNCHER 过滤器，
 *    想换图标就得关掉 MainActivity 本身，那是把 App 的入口一起关了。
 * ② 先开新的、再关旧的。反过来会有一瞬间整个 App 在桌面上消失，
 *    MIUI 那台机子实测会连带把最近任务里那条也刷掉。
 *
 * 真图标是 `res/drawable/ic_launcher_fg_*.xml` + `ic_launcher_bg_*.xml` 那几份；
 * 这里那三个颜色只给 App 里那排预览用，两边是同一套数，改一处要改两处。
 * （10-25 删了两枚：骨架期那枚方框「临时标记」，还有琥珀。方框删掉之后
 *   `<application android:icon>` 也指到蓝底那套了，别再往 `@mipmap/ic_launcher` 改回去 —— 那份资源没了。）
 */

/** 一枚可选的启动图标。 */
data class LauncherIcon(
    val key: String,
    val label: String,
    /** manifest 里那条 activity-alias 的类名（相对包名，前面带点） */
    val alias: String,
    val bg: Long,
    val ink: Long,
    val bar: Long,
)

object LauncherIcons {

    val ALL: List<LauncherIcon> = listOf(
        LauncherIcon("blue", "蓝底青杠", ".LauncherBlue", 0xFF2563EB, 0xFFFAFBFD, 0xFF0D9488),
        LauncherIcon("paper", "纸白墨杠", ".LauncherPaper", 0xFFF4F6F9, 0xFF191C22, 0xFF2563EB),
        LauncherIcon("ink", "墨底青杠", ".LauncherInk", 0xFF191C22, 0xFFFAFBFD, 0xFF0D9488),
        LauncherIcon("teal", "青底蓝杠", ".LauncherTeal", 0xFF0D9488, 0xFFFAFBFD, 0xFF2563EB),
        LauncherIcon("indigo", "靛底青杠", ".LauncherIndigo", 0xFF4338CA, 0xFFFAFBFD, 0xFF0D9488),
    )

    /** 桌面上现在立着的是哪一枚。全被关掉了（不该发生）就回第一枚。 */
    fun current(context: Context): LauncherIcon =
        ALL.firstOrNull { enabled(context, it) } ?: ALL.first()

    fun enabled(context: Context, icon: LauncherIcon): Boolean {
        val cn = ComponentName(context.packageName, context.packageName + icon.alias)
        val state = runCatching { context.packageManager.getComponentEnabledSetting(cn) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        // DEFAULT 对 alias 来说就是 manifest 里写的那个 enabled：只有第一枚是 true
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == ALL.first())
    }

    /**
     * 一条入口都没开着就把第一枚开回来。
     *
     * 为什么需要这一手：0.8.40 删掉了「临时标记」和「琥珀」两条 alias，而组件开关是**跨覆盖安装
     * 保留**的 —— 之前在这排里选过那两枚的人，机器上剩下五条全被显式写成 DISABLED，
     * 选中的那条又随包没了，桌面上就没有任何入口（只能从「设置 · 应用」里打开）。
     * 装完那一刻才是最早的机会，所以 `MY_PACKAGE_REPLACED` 那个广播里也调一次，
     * 进程每次起来再补一次。
     */
    fun ensureSomeEntry(context: Context) {
        if (ALL.any { enabled(context, it) }) return
        apply(context, ALL.first())
    }

    /**
     * 换上某一枚。先开新的再关旧的，理由见文件头。
     *
     * 这一步动的是系统里的组件开关，不是网络，也不是学校的什么数据 ——
     * 但它是"会改变桌面"的操作，所以界面上仍按写操作那一套：按下去才换，失败就把原因贴出来。
     */
    fun apply(context: Context, icon: LauncherIcon): Result<Unit> = runCatching {
        val pm = context.packageManager
        val target = ComponentName(context.packageName, context.packageName + icon.alias)
        pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP)
        ALL.filter { it.key != icon.key }.forEach { off ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, context.packageName + off.alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
        }
        Unit
    }
}
