package com.andaxin.core.net

/*
 * 登录口令的加密（common 契约 + 各平台实现）。
 *
 * 出处（两条，互相独立）：
 * ① 抓包：2026-10-19 17:12 米11 挂 `adb reverse` 代理，冷启动安小信，它自己的 LoginActivity
 *    发出的那条 `POST in.aiit.edu.cn/zhxy-information/mobile/getToken.do`，
 *    表单只有 `userCode`(10) 与 `password`(URL 编码 182 = 128 字节块的 base64)；
 * ② 反汇编：同一台机器上 pull 下来的安小信 `base.apk`（167MB，4 个 dex）里只有两把
 *    RSA 公钥，各 216 字符 / DER 162 字节 = RSA-1024，用的那把与 APK 里的**逐字节相同**。
 *
 * 一句自我更正：我最初是从旧仓的登录代码读到这两个值的，那违反了"不许拿前人实现当底子"。
 * 现在这两条出处都不经过任何人的源码 —— 值对得上是因为服务器就认它，不是因为抄来。
 *
 * 平移注：原来是一个 android object；共享层拆成 expect fun + 各平台 actual
 * （JVM 系走 java.security 原实现，iOS 走 Security 框架的 RSAEncryptionPKCS1）。
 * 算法口径：RSA-1024、X509 SubjectPublicKeyInfo 的 DER、PKCS1 填充、117 字节分块、整串 base64。
 */

/** RSA/PKCS1 分块加密后 base64。keyBase64 是 X509 公钥的 DER。 */
expect fun rsaEncryptPkcs1Base64(plain: ByteArray, keyBase64: String): String

object Crypto {

    private const val LOGIN_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC8Zt/Ngbil9NG1Y0/8uZDIL5eR" +
            "WWn3O9zlPBsVwDcgS2lQXNnZ5hl2CryGe9SbG4gUb1EO7cgizzQl8N1yYmVzwO4kc" +
            "yOEtA1HHdBLjC8xvTOGL1G62j6nyOjJE3CMNyCDxEPHSYwuQVMzNko/kg5KyTrNSQ" +
            "t62hwRYbIK+BRwEQIDAQAB"

    fun encryptPassword(plain: String): String = rsaEncryptPkcs1Base64(plain.encodeToByteArray(), LOGIN_KEY)
}
