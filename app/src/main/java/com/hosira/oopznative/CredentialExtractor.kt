package com.hosira.oopznative

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从网页 localStorage 的 dump 里找出登录凭证。
 * 规则（按优先级）：
 *  1. 键名含 signature/token/login/auth 的长值
 *  2. 值是 JSON 时递归进去找同样的键
 *  3. 退化为任意 32~200 位无空白高熵串
 *  4. 再退化为 cookie 里的长串
 */
object CredentialExtractor {

    fun extract(dump: String): Pair<String, String>? {
        val root = runCatching { JSONObject(dump) }.getOrNull() ?: return null
        var uid = ""
        var strong = ""
        var weak = ""

        fun consider(key: String, value: String) {
            if (value.isBlank() || value.length > 400) return
            val k = key.lowercase()
            val v = value.trim()
            when {
                k.contains("uid") && v.length in 6..40 && uid.isBlank() -> uid = v
                (k.contains("signature") || k.contains("token") || k.contains("login") || k.contains("auth")) &&
                    v.length in 20..200 && v.none { it.isWhitespace() } -> {
                    if (v.length > strong.length) strong = v
                }
                v.length in 32..200 && v.none { it.isWhitespace() } -> {
                    if (v.length > weak.length) weak = v
                }
            }
        }

        fun walk(node: Any?, depth: Int) {
            if (depth > 5) return
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    val v = node.opt(key)
                    if (v is String) {
                        consider(key, v)
                        if (v.trimStart().startsWith("{")) runCatching { walk(JSONObject(v), depth + 1) }
                    } else walk(v, depth + 1)
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
            }
        }

        walk(root, 0)
        val best = strong.ifBlank {
            val cookie = root.optString("COOKIE")
            if (cookie.isNotBlank()) {
                cookie.split(';').map { it.trim() }
                    .firstOrNull { it.length >= 32 }
                    ?.let { seg -> seg.substringAfter('=', seg) }   // 去掉 SESSDATA= 这类前缀
                    ?: weak
            } else weak
        }
        return if (best.isBlank()) null else uid to best
    }
}
