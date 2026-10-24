package com.andaxin.core.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系换算（纯数学，两端共用）。
 *
 * 平移注：原来和 `Tiles` 同住在 app 的 `core/map/Geo.kt`。体育族要进 logicMain，
 * 而 `RunPoint.where`、`RunDetail.track`、`RouteTemplate.points` 全带着 `LatLng`，
 * 所以把这半（类型 + WGS/GCJ/BD 三套换算）搬到 commonMain，
 * `Tiles`（墨卡托瓦片）仍留在 app —— 它只有高德瓦片那张地图用，iOS 这边还没接。
 *
 * 约定和上一版一样，但代码是本仓自己写的：本地一律存 GPS 的 WGS-84，
 * 只有两条边界换算 —— 画到高德瓦片上转 GCJ-02，交给学校转 BD-09。
 * 高德是 GCJ-02、百度是 BD-09，别记混：混一次就整体偏几百米。
 * （搬进 common 时 java.lang.Math 换成了 kotlin.math，公式一字没动。）
 */
data class LatLng(val latitude: Double, val longitude: Double)

object Geo {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = 3.14159265358979324 * 3000.0 / 180.0

    /** 一度纬度约多少米（算偏移量够用，不做椭球级精度） */
    const val METERS_PER_DEGREE_LAT = 111_320.0

    private fun rad(deg: Double): Double = deg / 180.0 * PI

    fun metersPerDegreeLng(lat: Double): Double = METERS_PER_DEGREE_LAT * cos(rad(lat))

    fun latDelta(meters: Double): Double = meters / METERS_PER_DEGREE_LAT

    fun lngDelta(meters: Double, atLat: Double): Double = meters / metersPerDegreeLng(atLat)

    /** 境外不偏移：国外没有 GCJ-02 这套事 */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        !(72.004 <= lng && lng <= 137.8347 && 0.8293 <= lat && lat <= 55.8271)

    fun wgsToGcj(lat: Double, lng: Double): LatLng {
        if (outOfChina(lat, lng)) return LatLng(lat, lng)
        var dLat = rotLat(lng - 105.0, lat - 35.0)
        var dLng = rotLng(lng - 105.0, lat - 35.0)
        val radLat = rad(lat)
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return LatLng(lat + dLat, lng + dLng)
    }

    /** GCJ-02 → WGS-84：没有闭式解，回代校正两次就够到厘米级 */
    fun gcjToWgs(lat: Double, lng: Double): LatLng {
        if (outOfChina(lat, lng)) return LatLng(lat, lng)
        var wLat = lat
        var wLng = lng
        repeat(2) {
            val back = wgsToGcj(wLat, wLng)
            wLat += lat - back.latitude
            wLng += lng - back.longitude
        }
        return LatLng(wLat, wLng)
    }

    fun wgsToBd(lat: Double, lng: Double): LatLng {
        val gcj = wgsToGcj(lat, lng)
        val z = sqrt(gcj.longitude * gcj.longitude + gcj.latitude * gcj.latitude) + 0.00002 * sin(gcj.latitude * X_PI)
        val theta = atan2(gcj.latitude, gcj.longitude) + 0.000003 * cos(gcj.longitude * X_PI)
        return LatLng(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    fun bdToWgs(lat: Double, lng: Double): LatLng {
        val z = sqrt((lng - 0.0065) * (lng - 0.0065) + (lat - 0.006) * (lat - 0.006))
        val theta = atan2(lat - 0.006, lng - 0.0065) - 0.000003 * cos((lng - 0.0065) * X_PI)
        val bdLng = z * cos(theta) - 0.00002 * sin((lat - 0.006) * X_PI)
        val bdLat = z * sin(theta) + 0.00002 * cos((lat - 0.006) * X_PI)
        return gcjToWgs(bdLat, bdLng)
    }

    /** 两点间米数（球面近似，轨迹长度够用） */
    fun metersBetween(a: LatLng, b: LatLng): Double {
        val dLat = (b.latitude - a.latitude) * METERS_PER_DEGREE_LAT
        val dLng = (b.longitude - a.longitude) * metersPerDegreeLng((a.latitude + b.latitude) / 2.0)
        return sqrt(dLat * dLat + dLng * dLng)
    }

    private fun rotLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun rotLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(y))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }
}
