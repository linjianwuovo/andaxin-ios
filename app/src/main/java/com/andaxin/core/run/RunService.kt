package com.andaxin.core.run

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.andaxin.AndaXinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 跑步期间的前台服务。
 *
 * 存在的唯一理由：屏幕灭了、系统回收后台时，采集和实况通知还得活着。
 * 引擎本身在 AppGraph 里，服务只是观察者 —— 服务被系统杀掉不该把用户这一场弄丢。
 *
 * **这条通知的节奏是每秒一次**（10-25 他点名的第二条）。节奏不在这儿，在 `RunEngine`
 * 那一路独立的计时协程里：它每秒把 `seconds` 按墙钟重算一次写回 state，这条 `collect`
 * 就跟着刷一次 —— 所以别把刷通知改成"攒几秒再发"或"只在来 GPS 点时发"：室内一颗点都进不来，
 * 通知会跟着一动不动（10-21 傍晚那次"0:06 不动"就是挂在点上造成的）。
 * 反复刷的是同一条号（`NOTIFICATION_ID`），这是更新一条通知，不是连发二十条。
 */
class RunService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as AndaXinApp).graph.engine
        RunNotifier.ensureChannel(this)
        val promoted = canPromote()

        startForeground(
            RunNotifier.NOTIFICATION_ID,
            RunNotifier.build(this, engine.state.value, promoted),
        )

        scope.launch {
            engine.state.collect { s ->
                if (!s.active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(RunNotifier.NOTIFICATION_ID, RunNotifier.build(this@RunService, s, promoted))
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 有没有被允许发提升通知。以前这儿是自己现查一句 `checkSelfPermission`，
     * 现在跟「更多功能」那一行问的是同一个地方（`LiveUpdates.canPost`）——
     * 两处问法不一样时，会出现"界面说已允许、发出来还是普通通知"，那种最难查。
     * 为什么两个都要问、为什么不能缓存，见 `core/notification/LiveUpdates.kt` 顶上那段。
     */
    private fun canPromote(): Boolean = com.andaxin.core.notification.LiveUpdates.canPost(this)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, RunService::class.java),
            )
        }
    }
}
