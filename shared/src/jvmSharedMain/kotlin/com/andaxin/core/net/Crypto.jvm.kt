package com.andaxin.core.net

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** JVM 系（Android/JVM）：就是原 app 里那份实现，一字节没动。1024 位密钥：单块最多 128-11=117 字节明文。 */
@OptIn(ExperimentalEncodingApi::class)
actual fun rsaEncryptPkcs1Base64(plain: ByteArray, keyBase64: String): String {
    val key = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(Base64.Default.decode(keyBase64)))
    val cipher = Cipher.getInstance("RSA/None/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val out = ByteArrayOutputStream()
    var offset = 0
    while (offset < plain.size) {
        val size = minOf(117, plain.size - offset)
        out.write(cipher.doFinal(plain, offset, size))
        offset += size
    }
    return Base64.Default.encode(out.toByteArray())
}
