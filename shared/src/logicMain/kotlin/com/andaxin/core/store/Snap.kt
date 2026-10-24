package com.andaxin.core.store

/**
 * 上一次拿到的首页数字，落在应用私有目录的 `snap_<name>.json`。
 *
 * 为什么要有它：首页要等六条并行接口全回来才落一次 state（"一次出"是规矩），
 * 冷启动时那 3–8 秒屏幕上是空的 —— 他看着就是"加载很慢"。
 * 现在先用上次的数字把界面填上，网络回来了再整屏换一次；界面会同时写着"更新中"，
 * 不会把旧数字冒充成刚拉到的。
 *
 * 只存首页这种"看一眼就走"的内容；成绩、请假这些带身份的单据不在这里缓存。
 *
 * 平移注：原来直接拿 Context + java.io.File，现在走 AppFiles（Android=filesDir，
 * iOS=Documents），文件名口径不变，老文件原样续读。
 */
object Snap {

    fun read(files: AppFiles, name: String): String? = runCatching {
        val f = "snap_$name.json"
        if (files.exists(f)) files.readText(f)?.takeIf { it.length > 2 } else null
    }.getOrNull()

    fun write(files: AppFiles, name: String, text: String) {
        runCatching { files.writeText("snap_$name.json", text) }
    }
}
