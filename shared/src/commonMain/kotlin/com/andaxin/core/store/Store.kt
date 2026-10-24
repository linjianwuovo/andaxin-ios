package com.andaxin.core.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.IOException
import okio.Path.Companion.toPath

// 全应用只有这一份首选项文件。
// 旧那套开了 13 个 DataStore 实例，每个都是一份文件加一条协程，全在启动期做磁盘 IO；
// 这里合成一份，键名带业务前缀（auth./theme./msg.）就够分了。
//
// 平移注：Android 侧路径必须指到 filesDir/datastore/andaxin.preferences_pb ——
// 那是老 preferencesDataStore(name="andaxin") 一直在写的同一份文件，指对了用户数据原样续用。

/** 设置与会话的本机入口：读用 Flow，写用 suspend edit，别在界面里直接碰 DataStore。 */
class Store(path: String) {

    private val data: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        produceFile = { path.toPath() },
    )

    val raw: Flow<Preferences> = data.data.catch { e ->
        // 读坏了（进程被杀时写一半）不能让整个流断掉，给一份空的继续跑
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    fun text(key: String): Flow<String?> = raw.map { it[stringPreferencesKey(key)] }

    /** 从已经拿到的一份快照里读值，不再触发一次 IO —— 聚合多个设置项时用这个。 */
    fun peekString(prefs: Preferences, key: String): String? = prefs[stringPreferencesKey(key)]

    /** 要一次性读好几个键、又不想订阅流的时候用这个拿快照。 */
    suspend fun snapshot(): Preferences = runCatching { data.data.first() }.getOrDefault(emptyPreferences())

    suspend fun setText(key: String, value: String?) {
        data.edit { p ->
            val k = stringPreferencesKey(key)
            if (value == null) p.remove(k) else p[k] = value
        }
    }

    /** 一批一起写。拖滑杆时逐帧 edit 会把磁盘打满，也会让读侧一帧内看到半新半旧。 */
    suspend fun setMany(vararg pairs: Pair<String, String?>) {
        data.edit { p ->
            pairs.forEach { (key, value) ->
                val k = stringPreferencesKey(key)
                if (value == null) p.remove(k) else p[k] = value
            }
        }
    }

    suspend fun clearAll() = data.edit { it.clear() }

    companion object {
        // 键名集中在这儿，散落各处的字符串键是下一版的坑
        const val KEY_TOKEN = "auth.token"
        const val KEY_USER_CODE = "auth.userCode"
        const val KEY_THEME_MODE = "theme.mode"
        const val KEY_ONBOARDED = "app.onboarded"
    }
}
