package com.andaxin.core.store

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/*
 * iOS 的 Vault：口令本体放 Keychain（kSecClassGenericPassword，ThisDeviceOnly ——
 * 换机/备份带不走，语义对齐 Android 侧"密钥不可导出"那条线）。
 * seal 回的是固定句柄 "keychain:v1"，DataStore 里存的就是这个句柄而不是密文
 * ——两平台的 blob 格式本来就各是各的（都只在本机转一圈），接口语义不变。
 * 登出要记得 vault.discard()：句柄跟 DataStore clearAll 一起没了，
 * Keychain 里那条得靠这里删，不然口令还躺在钥匙串里。
 */
actual fun createVault(): Vault = KeychainVault()

@OptIn(ExperimentalForeignApi::class)
class KeychainVault : Vault {

    override fun seal(plain: String): Result<String> = runCatching {
        write(plain.encodeToByteArray())
        HANDLE
    }

    override fun open(blob: String): String? {
        if (blob != HANDLE) return null
        return read()?.decodeToString()
    }

    override fun discard() {
        memScoped { SecItemDelete(baseQuery(this)) }
    }

    private val service: CFStringRef =
        CFStringCreateWithCString(null, "com.andaxin.prefs.vault", kCFStringEncodingUTF8)!!
    private val account: CFStringRef =
        CFStringCreateWithCString(null, "remembered-password", kCFStringEncodingUTF8)!!

    private fun read(): ByteArray? = memScoped {
        val query = baseQuery(this)
        CFDictionarySetValue(query, kSecReturnData, kCFTrue())
        val out = alloc<CFTypeRefVar>()
        if (SecItemCopyMatching(query, out.ptr) != 0) return@memScoped null
        // 回来的是 CFDataRef（我们只请求了 kSecReturnData），从 CFTypeRef 收窄一下类型而已
        @Suppress("UNCHECKED_CAST")
        val data = out.value as? CFDataRef ?: return@memScoped null
        val len = CFDataGetLength(data)
        if (len <= 0L) return@memScoped null
        CFDataGetBytePtr(data)?.readBytes(len.toInt())
    }

    private fun write(bytes: ByteArray) = memScoped {
        SecItemDelete(baseQuery(this))
        val add = baseQuery(this)
        CFDictionarySetValue(add, kSecValueData, bytes.cfData())
        CFDictionarySetValue(add, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(add, null)
        check(status == 0) { "Keychain 写入失败（SecItemAdd=$status）" }
    }

    private fun baseQuery(scope: MemScope): CFMutableDictionaryRef {
        val q = CFDictionaryCreateMutable(null, 5L, null, null)!!
        CFDictionarySetValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(q, kSecAttrService, service)
        CFDictionarySetValue(q, kSecAttrAccount, account)
        return q
    }

    private fun ByteArray.cfData(): CFDataRef? =
        usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), size.toLong()) }

    private fun kCFTrue() = platform.CoreFoundation.kCFBooleanTrue

    private companion object {
        const val HANDLE = "keychain:v1"
    }
}
