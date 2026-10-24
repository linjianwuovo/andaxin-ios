package com.andaxin.data.capture

import com.andaxin.core.map.Geo
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/**
 * 跑步上传那条 `list` 字段的拼包。
 *
 * 形状不是猜的：旧项目里已经对着官方 APK 的 `OkHttpHelper.upLoadSport` 和抓包逐项核过，
 * 而且 10-15 真交上去过一条学校判「有效」的 3.14km。这一版把它从协议抄回来，代码是新写的。
 *
 * 十个键，顺序固定：
 * `exerciseId, memberId, runningType, startDate, endDate, mile, time, speed, runningRoute, sourceInfo`
 *
 * **键名全部 RSA 加密；值里九个加密，唯独 `runningRoute` 是明文。**
 * 这条不是审美：以前把轨迹也加密，服务器 mile/speed/time 都能解，偏偏轨迹解不开，
 * 记录建出来了却判「运动轨迹异常」，整场无效。
 *
 * 空值（`exerciseId` 为空或 "null"）按官方那样：键名照样加密，值给空串，不再加密。
 */
object SportsUploadCodec {

    /** 官方 APK 里跑步数据用的那把公钥（RSA-1024，X.509 SubjectPublicKeyInfo）。 */
    private const val PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQChZH5KNDLzeINf6N+fNMAv4BJb4J" +
            "nC3JTvSWAS4IVyGVI1xXQW4odl0hXl1AUuR4FbVFDCEIjp+YEeLELPMiTOlAPg9Ps7" +
            "vp4wrrB/J7bHdlkyx+b8YztPOlU/wDzqL5H5sxmivpKrHvBRUCSMfBpN2czkuUJhbE" +
            "9zgJMyxZC1gwIDAQAB"

    /** 1024 位密钥一块 128 字节，PKCS#1 v1.5 占 11 字节。 */
    private const val BLOCK = 117

    /** 官方表单里那个写死的 appId。 */
    const val APP_ID = "11111111111111111111111111111111"

    data class Run(
        val exerciseId: String,
        val memberId: String,
        val runningType: String,
        val startText: String,
        val endText: String,
        val mileKm: Double,
        val timeSeconds: Long,
        val speedKmh: Double,
        /** WGS-84；拼包时统一转 BD-09。 */
        val trackWgs: List<Pair<Double, Double>>,
        val deviceModel: String,
    )

    fun buildListField(run: Run): String {
        val obj = JSONObject()
        putField(obj, "exerciseId", run.exerciseId)
        putField(obj, "memberId", run.memberId)
        putField(obj, "runningType", run.runningType)
        putField(obj, "startDate", run.startText)
        putField(obj, "endDate", run.endText)
        putField(obj, "mile", com.andaxin.core.text.fixed(run.mileKm, 2))
        putField(obj, "time", run.timeSeconds.toString())
        putField(obj, "speed", com.andaxin.core.text.fixed(run.speedKmh, 2))
        // 只加密键名；值必须是明文，理由见类注释
        obj.put(encrypt("runningRoute"), trackJson(run.trackWgs))
        putField(obj, "sourceInfo", run.deviceModel)
        return JSONArray().put(obj).toString()
    }

    private fun putField(obj: JSONObject, key: String, value: String) {
        val v = value.trim()
        if (v.isEmpty() || v.equals("null", ignoreCase = true)) {
            obj.put(encrypt(key), "")
        } else {
            obj.put(encrypt(key), encrypt(v))
        }
    }

    /** 官方存的是 BD-09、六位小数、`latitude` 在前。 */
    private fun trackJson(trackWgs: List<Pair<Double, Double>>): String {
        val sb = StringBuilder("[")
        trackWgs.forEachIndexed { i, (lat, lng) ->
            val bd = Geo.wgsToBd(lat, lng)
            if (i > 0) sb.append(',')
            sb.append("{\"latitude\":")
                .append(com.andaxin.core.text.fixed(bd.latitude, 6))
                .append(",\"longitude\":")
                .append(com.andaxin.core.text.fixed(bd.longitude, 6))
                .append('}')
        }
        return sb.append(']').toString()
    }

    // 平移注：分块加密那套与登录那把同构，直接走共享层的 rsaEncryptPkcs1Base64（117 分块、PKCS1、整串 base64）。
    fun encrypt(plain: String): String =
        com.andaxin.core.net.rsaEncryptPkcs1Base64(plain.encodeToByteArray(), PUBLIC_KEY)
}
