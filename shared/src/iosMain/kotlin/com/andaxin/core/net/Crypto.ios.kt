package com.andaxin.core.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.CoreFoundation.CFErrorRefVar
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmRSAEncryptionPKCS1

/**
 * iOS：Security 框架的 RSA/PKCS1。keyBase64 解出来就是 X509 SubjectPublicKeyInfo 的 DER，
 * SecKeyCreateWithData 直接吃这个格式；117 字节分块与 JVM 侧口径一致。
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
actual fun rsaEncryptPkcs1Base64(plain: ByteArray, keyBase64: String): String {
    val keyDer = Base64.Default.decode(keyBase64)
    val key: SecKeyRef = memScoped {
        val derData: CFDataRef? = keyDer.usePinned {
            CFDataCreate(null, it.addressOf(0).reinterpret(), keyDer.size.toLong())
        } ?: throw IllegalStateException("公钥 DER 造不出来")
        val attrs = CFDictionaryCreateMutable(null, 2L, null, null)
        CFDictionarySetValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeRSA)
        val keyErr = alloc<CFErrorRefVar>()
        SecKeyCreateWithData(derData, attrs, keyErr.ptr)
            ?: throw IllegalStateException("公钥解析失败（不是合法的 X509 RSA DER）")
    }

    val out = ArrayList<Byte>(plain.size / 117 * 128 + 128)
    var offset = 0
    while (offset < plain.size) {
        val size = minOf(117, plain.size - offset)
        val block = plain.copyOfRange(offset, offset + size)
        memScoped {
            val err = alloc<platform.CoreFoundation.CFErrorRefVar>()
            val blockData: CFDataRef? = block.usePinned {
                CFDataCreate(null, it.addressOf(0).reinterpret(), block.size.toLong())
            }
            val encrypted = SecKeyCreateEncryptedData(
                key,
                kSecKeyAlgorithmRSAEncryptionPKCS1,
                blockData,
                err.ptr,
            )
            if (encrypted == null) {
                val reason = "见控制台"
                throw IllegalStateException("RSA 加密失败（SecKeyCreateEncryptedData 回空，$reason）")
            }
            val len = CFDataGetLength(encrypted)
            CFDataGetBytePtr(encrypted)?.readBytes(len.toInt())?.let { out.addAll(it.toList()) }
        }
        offset += size
    }
    return Base64.Default.encode(out.toByteArray())
}
