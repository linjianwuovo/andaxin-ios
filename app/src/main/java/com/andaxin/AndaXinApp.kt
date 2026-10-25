package com.andaxin

import android.app.Application
import com.andaxin.app.AppGraph
import com.andaxin.core.launcher.LauncherIcons

class AndaXinApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // 只建壳，feed 全是 lazy：启动期不做任何磁盘或网络 IO
        graph = AppGraph(this)
        graph.poller.start()
        // 唯一的例外，两条读 + 极少数机器上一条写：桌面上要是一条入口都没了（删过 alias 的
        // 那批人升级之后会这样），开一次就把第一枚补回来。正常路径是"已经有一枚开着"，直接返回。
        LauncherIcons.ensureSomeEntry(this)
    }
}
