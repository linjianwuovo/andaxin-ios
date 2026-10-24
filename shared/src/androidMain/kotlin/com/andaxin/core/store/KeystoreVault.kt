package com.andaxin.core.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/*
 * Android 的 Vault：Android Keystore 里一把不可导出的 AES-256-GCM 密钥。
 * 这是 app 里 core/store/KeystoreVault.kt 的原样平移（那个类拿 Context 只是为了
 * applicationContext，从没用到 —— 这里干脆不收）。取证注释都在原文件，搬家时一起带走。
 * 不绑指纹/锁屏的口径保持：绑了每次重登都要生物识别。
 */
actual fun createVault(): Vault = KeystoreVault()

class KeystoreVault : AesGcmVault() {

    override fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "com.andaxin.prefs.vault"
    }
}
