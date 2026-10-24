package com.andaxin.core.store

/**
 * 「记住密码」的密封接口。语义沿用 app 里那把 Android Keystore 的 AES-256-GCM：
 * 密文 base64 落盘（IV 拼在密文前），解不开一律当"没有记住"，绝不让登录屏打不开。
 * 各平台实现：Android = Keystore（不可导出密钥）；iOS = Keychain（ThisDeviceOnly 条目）；
 * JVM = 内存密钥，**只是本地测试架，绝不出货**。
 */
interface Vault {
    /** 失败不吞原因（界面上要显示「存不下：xxx」）。 */
    fun seal(plain: String): Result<String>

    /** 密文坏了 / 密钥没了都回 null。 */
    fun open(blob: String): String?

    /** 登出时把记住的口令抹掉。Android 的密文跟着 DataStore 走，无需实现；Keychain 那侧靠它。 */
    fun discard() {}

    fun usable(blob: String): Boolean = !open(blob).isNullOrBlank()
}

expect fun createVault(): Vault
