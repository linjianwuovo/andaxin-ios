@file:Suppress("unused")

package org.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * org.json 兼容垫片（非 Android 目标专用）。
 *
 * 为什么存在：data/capture 那 40 来个文件满地是 JSONObject/JSONArray，而 org.json 官方
 * 只发 JVM jar（2026-10-24 探针实测）。Android 端继续用平台自带那份，行为零变化；
 * iOS/JVM 走这份垫片，kotlinx-serialization 做 backend。
 *
 * 覆盖面 = 全仓实测用到的 16 个方法（约 870 处调用）：put、optString、optInt、optLong、
 * optDouble、optBoolean、optJSONObject、optJSONArray、getString、getInt、getJSONObject、
 * getJSONArray、has、remove、keys、length、toString、join。
 * 不在覆盖面里的方法不要用——用到了编译就报错，正好逼着补。
 *
 * 与 org.json 的已知差异（都无害，记在这儿防翻旧账）：
 * 1. 数字原样保留字面量："1.00" 解出来 optString 是 "1.00"（org.json 会归一成 "1"）；
 *    显示层要的是服务端原话，这个更忠。
 * 2. toString 不把 "/" 转义成 "\/"（org.json 会）；两者是等价 JSON，服务端都认。
 * 3. 解析失败抛 IllegalArgumentException（org.json 抛 JSONException）——调用方全是
 *    runCatching 包着，不区分这个。
 */

private val LENIENT = Json { isLenient = true; ignoreUnknownKeys = true }

/** org.json 那个带 indentFactor 的缩进打印口径：每层缩 indentFactor 格，空容器单行。 */
internal fun jsonPretty(el: JsonElement, indentFactor: Int): String {
    val sb = StringBuilder()
    fun pad(level: Int) { repeat(indentFactor * level) { sb.append(' ') } }
    fun walk(e: JsonElement, level: Int) {
        when (e) {
            is JsonObject -> {
                if (e.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                e.entries.forEachIndexed { i, (k, v) ->
                    pad(level + 1)
                    sb.append(JsonPrimitive(k)).append(": ")
                    walk(v, level + 1)
                    if (i != e.size - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(level); sb.append('}')
            }
            is JsonArray -> {
                if (e.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                e.forEachIndexed { i, v ->
                    pad(level + 1)
                    walk(v, level + 1)
                    if (i != e.size - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(level); sb.append(']')
            }
            else -> sb.append(e.toString())
        }
    }
    walk(el, 0)
    return sb.toString()
}

internal fun parseElement(text: String): JsonElement = LENIENT.parseToJsonElement(text)

internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this)
    is JSONObject -> buildJsonObject { this@toJsonElement.map.forEach { (k, v) -> put(k, v) } }
    is JSONArray -> JsonArray(this@toJsonElement.toList())
    else -> JsonPrimitive(this.toString())
}

/** JsonElement → Kotlin 值，口径照 org.json 的 opt：数字给 Long/Double、布尔给 Boolean、串给 String。 */
internal fun JsonElement?.toKotlin(): Any? = when (this) {
    null, is JsonNull -> null
    is JsonObject -> JSONObject(this)
    is JsonArray -> JSONArray(this)
    is JsonPrimitive -> if (isString) content else content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
}

internal fun JsonElement?.coerceString(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}

internal fun JsonElement?.coerceInt(default: Int): Int = when (this) {
    null, is JsonNull -> default
    is JsonPrimitive -> content.trim().toDoubleOrNull()?.toInt() ?: default
    else -> default
}

internal fun JsonElement?.coerceLong(default: Long): Long = when (this) {
    null, is JsonNull -> default
    is JsonPrimitive -> content.trim().toDoubleOrNull()?.toLong() ?: default
    else -> default
}

internal fun JsonElement?.coerceDouble(default: Double): Double = when (this) {
    null, is JsonNull -> default
    is JsonPrimitive -> content.trim().toDoubleOrNull() ?: default
    else -> default
}

internal fun JsonElement?.coerceBoolean(default: Boolean): Boolean = when (this) {
    null, is JsonNull -> default
    is JsonPrimitive -> if (isString) content.equals("true", ignoreCase = true) else content.toBooleanStrictOrNull() ?: default
    else -> default
}

class JSONObject {

    internal val map = LinkedHashMap<String, JsonElement>()

    constructor()

    constructor(text: String) {
        val root = parseElement(text)
        require(root is JsonObject) { "不是 JSON 对象：${text.take(40)}" }
        root.forEach { (k, v) -> map[k] = v }
    }

    internal constructor(copy: JsonObject) {
        copy.forEach { (k, v) -> map[k] = v }
    }

    fun length(): Int = map.size

    fun has(name: String): Boolean = map.containsKey(name)

    fun isNull(name: String): Boolean = map[name] is JsonNull

    fun opt(name: String): Any? = map[name].toKotlin()

    fun optString(name: String, default: String): String =
        map[name]?.let { if (it is JsonNull) default else it.coerceString().ifEmpty { default } } ?: default

    fun optString(name: String): String = map[name].coerceString()

    fun optInt(name: String, default: Int): Int = map[name].coerceInt(default)
    fun optInt(name: String): Int = optInt(name, 0)

    fun optLong(name: String, default: Long): Long = map[name].coerceLong(default)
    fun optLong(name: String): Long = optLong(name, 0L)

    fun optDouble(name: String, default: Double): Double = map[name].coerceDouble(default)
    fun optDouble(name: String): Double = optDouble(name, 0.0)

    fun optBoolean(name: String, default: Boolean): Boolean = map[name].coerceBoolean(default)
    fun optBoolean(name: String): Boolean = optBoolean(name, false)

    fun optJSONObject(name: String): JSONObject? = (map[name] as? JsonObject)?.let { JSONObject(it) }

    fun optJSONArray(name: String): JSONArray? = (map[name] as? JsonArray)?.let { JSONArray(it) }

    fun getJSONObject(name: String): JSONObject = optJSONObject(name) ?: throw IllegalArgumentException("JSONObject[$name] 不是对象")

    fun getJSONArray(name: String): JSONArray = optJSONArray(name) ?: throw IllegalArgumentException("JSONObject[$name] 不是数组")

    fun getString(name: String): String {
        val v = map[name]
        if (v == null || v is JsonNull) throw IllegalArgumentException("JSONObject[$name] 没有值")
        return v.coerceString()
    }

    fun getInt(name: String): Int {
        val v = map[name]
        if (v == null || v is JsonNull || v !is JsonPrimitive) throw IllegalArgumentException("JSONObject[$name] 不是数字")
        return v.content.trim().toDoubleOrNull()?.toInt()
            ?: throw IllegalArgumentException("JSONObject[$name] 不是数字")
    }

    /** 返回 this 便于链式；value 为 null = 移除该键（org.json 的口径）。 */
    fun put(name: String, value: Any?): JSONObject {
        if (value == null) map.remove(name) else map[name] = value.toJsonElement()
        return this
    }

    fun remove(name: String): Any? = map.remove(name).toKotlin()

    fun keys(): Iterator<String> = map.keys.iterator()

    fun keySet(): Set<String> = map.keys

    override fun toString(): String = JsonObject(map).toString()

    fun toString(indentFactor: Int): String =
        if (indentFactor <= 0) toString() else jsonPretty(JsonObject(map), indentFactor)
}
