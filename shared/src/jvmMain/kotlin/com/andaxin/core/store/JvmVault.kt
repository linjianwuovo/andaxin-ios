package com.andaxin.core.store

import javax.crypto.KeyGenerator

/*
 * JVM 测试架专用的 Vault：内存里一把随机 AES 密钥，进程重启即丢。
 * 只服务于本机 jvmTest（垫片/缝的语义验证），**绝不出货、绝不落任何盘**。
 */
actual fun createVault(): Vault = JvmVault()

class JvmVault : AesGcmVault() {

    private val memKey by lazy {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun key(): javax.crypto.SecretKey = memKey
}
