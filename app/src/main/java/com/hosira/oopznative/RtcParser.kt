package com.hosira.oopznative

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从接口响应里提取 TRTC 进房凭证。
 * 字段名随 Oopz 客户端版本变化，这里做宽松匹配（大小写、下划线、嵌套层级都不敏感）。
 */
object RtcParser {

    data class Credentials(
        val sdkAppId: Int,
        val userId: String,
        val userSig: String,
        val roomId: String
    )

    fun parse(json: String): Credentials? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        var sdkAppId = -1
        var userSig = ""
        var userId = ""
        var roomId = ""

        fun take(key: String, value: Any?) {
            val flat = key.lowercase().replace("_", "").replace("-", "")
            when (flat) {
                "sdkappid", "appid", "sdkid" -> if (sdkAppId <= 0) (value as? Any)?.let {
                    sdkAppId = when (it) {
                        is Int -> it
                        is Number -> it.toInt()
                        is String -> it.toIntOrNull() ?: sdkAppId
                        else -> sdkAppId
                    }
                }
                "usersig", "sig", "userstoken" -> if (userSig.isBlank() && value is String) userSig = value
                "userid", "uid", "trtcuserid" -> if (userId.isBlank() && value is String) userId = value
                "strroomid", "roomid", "room" -> if (roomId.isBlank() && value != null) roomId = value.toString()
            }
        }

        fun walk(node: Any?, depth: Int) {
            if (depth > 6) return
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    val v = node.opt(key)
                    take(key, v)
                    if (v is JSONObject || v is JSONArray) walk(v, depth + 1)
                    else if (v is String && v.trimStart().startsWith("{")) {
                        runCatching { walk(JSONObject(v), depth + 1) }
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
            }
        }

        walk(root, 0)
        // 有的接口用数字 roomId；字符串 roomId 为空时也接受纯数字
        if (roomId.isBlank()) roomId = root.optLong("roomId", 0L).let { if (it > 0) it.toString() else "" }
        return if (sdkAppId > 0 && userSig.isNotBlank()) Credentials(sdkAppId, userId, userSig, roomId) else null
    }
}
