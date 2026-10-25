package com.andaxin.core.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 覆盖安装完成那一条广播，只做一件事：把桌面入口补回来（见 `LauncherIcons.ensureSomeEntry`）。
 *
 * 为什么非得挂在广播上、不能只等 App 起来的时候自愈：删掉一条 alias 之后，机器上有可能
 * **一条入口都不剩** —— 那时候 App 根本打不开，"下次启动补一刀"就是句空话。
 * 这一发是系统装完包主动送过来的，是那次升级之后最早的机会。
 */
class ReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LauncherIcons.ensureSomeEntry(context)
    }
}
