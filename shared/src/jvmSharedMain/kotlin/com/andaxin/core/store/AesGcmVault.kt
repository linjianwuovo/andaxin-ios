package com.andaxin.core.store

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/*
 * JVM 系（Android/JVM）共用的 Vault 骨架：AES-256-GCM，密文 = 12 字节 IV + 密文+tag，
 * base64（无换行）落盘 —— 和 app 里原来那把 KeystoreVault 逐字节同格式，密文互认。
 * 密钥从哪来由子类给（Android = Keystore 不可导出密钥；JVM = 内存密钥，纯测试架）。
 */
abstract class AesGcmVault : Vault {

    protected abstract fun key(): javax.crypto.SecretKey

    override fun seal(plain: String): Result<String> = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(iv + ct)
    }

    override fun open(blob: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(blob)
        if (raw.size <= IV_LEN) return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, IV_LEN)))
        String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
