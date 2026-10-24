package com.andaxin.shared

import com.andaxin.core.concurrency.nowMillis
import com.andaxin.core.net.RawCookie
import com.andaxin.core.net.formUrlEncode
import com.andaxin.core.net.matches
import com.andaxin.core.store.createVault
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/*
 * 缝与垫片的语义测试，跑在 JVM 目标上（Windows 本机就能跑，不用等 CI）。
 * 用例全部对着真实报文里的坑来的：字符串数字、null 的 data、分页壳、双编码名、cookie 域匹配。
 */

class JsonShimTest {

    @Test
    fun 校方信封_三件套() {
        val root = JSONObject("""{"flag":true,"code":200,"msg":"成功","data":{"rows":[]}}""")
        assertTrue(root.optBoolean("flag"))
        assertEquals("成功", root.optString("msg"))
        assertTrue(root.optJSONObject("data") != null)
        assertEquals(0, root.optJSONObject("data")!!.optJSONArray("rows")!!.length())
    }

    @Test
    fun 字符串数字能取成整数() {
        // FIF 那族 videoSize 是字符串（接入清单 D1），取数侧要能转
        val root = JSONObject("""{"videoSize":"1024","count":"12","ok":"true"}""")
        assertEquals(1024, root.optInt("videoSize"))
        assertEquals(12, root.optInt("count"))
        assertTrue(root.optBoolean("ok"))
    }

    @Test
    fun 缺键与null各回各的默认() {
        val root = JSONObject("""{"a":null,"b":1}""")
        assertEquals("", root.optString("a"))
        assertEquals("缺了", root.optString("zzz", "缺了"))
        assertEquals(0, root.optInt("a"))
        assertEquals(7, root.optInt("zzz", 7))
        assertTrue(root.isNull("a"))
        assertFalse(root.has("zzz"))
        assertNull(root.optJSONObject("a"))
    }

    @Test
    fun 分页壳_数组藏两层() {
        val page = JSONObject("""{"flag":true,"data":{"data":[{"id":1},{"id":"2"}]}}""")
        val arr = page.optJSONObject("data")!!.optJSONArray("data")!!
        assertEquals(2, arr.length())
        assertEquals(1, arr.optJSONObject(0)!!.optInt("id"))
        // "2" 这种字符串数字也要能取
        assertEquals(2, arr.optJSONObject(1)!!.optInt("id"))
    }

    @Test
    fun 数组直接挂根节点rows() {
        val root = JSONObject("""{"flag":true,"rows":[{"a":1},{"a":2},{"a":3}]}""")
        assertEquals(3, root.optJSONArray("rows")!!.length())
        assertEquals("1,2,3", buildString {
            val rows = root.optJSONArray("rows")!!
            for (i in 0 until rows.length()) append(if (i == 0) "" else ",").append(rows.optJSONObject(i)!!.optInt("a"))
        })
    }

    @Test
    fun put构造体_中文与转义回环() {
        val body = JSONObject()
            .put("access_token", "abc.def")
            .put("_userName", "黄同学")
            .put("appId", "11111111111111111111111111111111")
        val text = body.toString()
        val back = JSONObject(text)
        assertEquals("黄同学", back.optString("_userName"))
        assertEquals("abc.def", back.optString("access_token"))
    }

    @Test
    fun put_null是移除不是存null() {
        val root = JSONObject().put("a", "x").put("b", "y")
        root.put("a", null)
        assertFalse(root.has("a"))
        assertTrue(root.has("b"))
    }

    @Test
    fun put嵌套对象和数组() {
        val inner = JSONObject().put("k", "v")
        val arr = JSONArray().put(1).put("two").put(false)
        val root = JSONObject().put("obj", inner).put("arr", arr)
        assertEquals("v", root.optJSONObject("obj")!!.optString("k"))
        assertEquals(3, root.optJSONArray("arr")!!.length())
        assertEquals("two", root.optJSONArray("arr")!!.optString(1))
        assertFalse(root.optJSONArray("arr")!!.optBoolean(2) )
        assertEquals("1,\"two\",false", arr.join(","))
    }

    @Test
    fun 回来的不是JSON要抛() {
        val html = "<html><body>登录页</body></html>"
        var threw = false
        try { JSONObject(html) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun getString缺键抛_有的话按字符串取() {
        val root = JSONObject("""{"n":5,"s":"x"}""")
        assertEquals("x", root.getString("s"))
        assertEquals("5", root.getString("n"))
        var threw = false
        try { root.getString("nope") } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }
}

class FormEncodeTest {

    @Test
    fun 与java的URLEncoder逐字节一致() {
        val samples = listOf(
            "黄同学", "Li Na", "a.b-c*d_e~f", "100%", "中文+and+symbols:!@#\$^&()",
            "aiit+20260001", "", "tab\there", "emoji🙂",
        )
        for (s in samples) {
            assertEquals(URLEncoder.encode(s, "UTF-8"), formUrlEncode(s), "样本：[$s]")
        }
    }
}

class CookieMatchTest {

    private fun cookie(domain: String, path: String) = RawCookie("n", "v", domain, path, Long.MAX_VALUE)

    @Test
    fun 同域同路径全中() {
        assertTrue(cookie("sttp.fifedu.com", "/").matches("sttp.fifedu.com", "/api/x"))
        assertTrue(cookie(".fifedu.com", "/").matches("resc.fifedu.com", "/a"))
    }

    @Test
    fun 别的域不带() {
        assertFalse(cookie("sttp.fifedu.com", "/").matches("in.aiit.edu.cn", "/"))
        assertFalse(cookie("fifedu.com", "/").matches("xfifedu.com", "/"))
    }

    @Test
    fun 路径前缀要对齐边界() {
        assertTrue(cookie("a.com", "/app").matches("a.com", "/app/file"))
        assertFalse(cookie("a.com", "/app").matches("a.com", "/apple"))
        assertTrue(cookie("a.com", "/").matches("a.com", "/any/thing"))
    }
}

class VaultTest {

    @Test
    fun seal后open回原文() {
        val vault = createVault()
        val blob = vault.seal("校园口令#2026!").getOrThrow()
        assertEquals("校园口令#2026!", vault.open(blob))
        assertTrue(vault.usable(blob))
    }

    @Test
    fun 坏密文回null不抛() {
        val vault = createVault()
        assertNull(vault.open("这不是base64的密文!!"))
    }
}
