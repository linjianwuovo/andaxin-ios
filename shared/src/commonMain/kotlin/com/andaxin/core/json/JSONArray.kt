package com.andaxin.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** org.json 兼容垫片的数组那一半（iOS actual），口径说明见 JSONObject.kt 文件头。 */
class JSONArray {

    private val list = ArrayList<JsonElement>()

    constructor()

    constructor(text: String) {
        val root = parseElement(text)
        require(root is JsonArray) { "不是 JSON 数组：${text.take(40)}" }
        list.addAll(root)
    }

    internal constructor(copy: JsonArray) {
        list.addAll(copy)
    }

    internal fun toList(): List<JsonElement> = list

    fun length(): Int = list.size

    fun put(value: Any?): JSONArray {
        list.add(value.toJsonElement())
        return this
    }

    fun opt(index: Int): Any? = list.getOrNull(index).toKotlin()

    fun optJSONObject(index: Int): JSONObject? = (list.getOrNull(index) as? JsonObject)?.let { JSONObject(it) }

    fun optJSONArray(index: Int): JSONArray? = (list.getOrNull(index) as? JsonArray)?.let { JSONArray(it) }

    fun optString(index: Int, default: String): String =
        list.getOrNull(index)?.let { if (it is JsonNull) default else it.coerceString().ifEmpty { default } } ?: default

    fun optString(index: Int): String = list.getOrNull(index).coerceString()

    fun optInt(index: Int, default: Int): Int = list.getOrNull(index).coerceInt(default)
    fun optInt(index: Int): Int = optInt(index, 0)

    fun optLong(index: Int, default: Long): Long = list.getOrNull(index).coerceLong(default)

    fun optLong(index: Int): Long = optLong(index, 0L)

    fun optDouble(index: Int, default: Double): Double = list.getOrNull(index).coerceDouble(default)

    fun optDouble(index: Int): Double = optDouble(index, 0.0)

    fun optBoolean(index: Int, default: Boolean): Boolean = list.getOrNull(index).coerceBoolean(default)

    fun optBoolean(index: Int): Boolean = optBoolean(index, false)

    fun getJSONObject(index: Int): JSONObject = optJSONObject(index) ?: throw IllegalArgumentException("JSONArray[$index] 不是对象")

    fun getJSONArray(index: Int): JSONArray = optJSONArray(index) ?: throw IllegalArgumentException("JSONArray[$index] 不是数组")

    fun getString(index: Int): String {
        val v = list.getOrNull(index)
        if (v == null || v is JsonNull) throw IllegalArgumentException("JSONArray[$index] 没有值")
        return v.coerceString()
    }

    fun remove(index: Int): Any? = if (index in list.indices) list.removeAt(index).toKotlin() else null

    /** org.json 的 join：字符串成员带引号、非字符串成员原样，用分隔符串起来。 */
    fun join(separator: String): String = list.joinToString(separator) { el ->
        when {
            el is JsonNull -> "null"
            el is JsonPrimitive && el.isString -> JsonArray(listOf(el)).toString().removePrefix("[").removeSuffix("]")
            else -> el.toString()
        }
    }

    override fun toString(): String = JsonArray(list).toString()

    fun toString(indentFactor: Int): String =
        if (indentFactor <= 0) toString() else jsonPretty(JsonArray(list), indentFactor)
}
