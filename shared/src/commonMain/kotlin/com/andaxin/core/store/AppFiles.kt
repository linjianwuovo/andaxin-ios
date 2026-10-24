package com.andaxin.core.store

/**
 * 应用私有目录里的具名文件（校园卡 token、跑步路线、快照这些）。
 * Android 的 filesDir 和 iOS 的 Documents 由各平台在装配时给实现，业务只认这四个动作。
 * name 是相对路径（允许 "checkin/x.jpg" 这种一层子目录），别的花样不许。
 */
interface AppFiles {
    fun readText(name: String): String?
    fun writeText(name: String, text: String)
    fun readBytes(name: String): ByteArray?
    fun writeBytes(name: String, bytes: ByteArray)
    fun exists(name: String): Boolean
    fun delete(name: String)
}
