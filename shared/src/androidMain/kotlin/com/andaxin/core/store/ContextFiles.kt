package com.andaxin.core.store

import android.content.Context
import java.io.File

/**
 * Android 的 AppFiles：就是原来那套 filesDir 读写，路径口径不变（snap_xxx.json、
 * ecard_token.txt、sport_routes.json 这些老文件原样续用）。
 */
class ContextFiles(context: Context) : AppFiles {

    private val root: File = context.filesDir

    private fun f(name: String): File {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        return file
    }

    override fun readText(name: String): String? =
        runCatching { f(name).takeIf { it.exists() }?.readText() }.getOrNull()

    override fun writeText(name: String, text: String) {
        f(name).writeText(text)
    }

    override fun readBytes(name: String): ByteArray? =
        runCatching { f(name).takeIf { it.exists() }?.readBytes() }.getOrNull()

    override fun writeBytes(name: String, bytes: ByteArray) {
        f(name).writeBytes(bytes)
    }

    override fun exists(name: String): Boolean = f(name).exists()

    override fun delete(name: String) {
        f(name).delete()
    }
}
