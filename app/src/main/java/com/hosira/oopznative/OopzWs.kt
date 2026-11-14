package com.hosira.oopznative

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Oopz 网关 wss://ws.oopz.cn/
 * 握手：{"time":ms,"event":253,"body":"{person,deviceId,signature,deviceName}"}
 * 实测：握手后网关回 {"event":21,"body":"{\"checkRes\":false}"} 并断开 —— checkRes 即身份校验结果，
 * false 说明签名无效/过期，需要重新走网页登录。
 */
class OopzWs(private val session: OopzApi.Session, private val deviceId: String) {

    data class Event(val id: Int, val body: JSONObject?)

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(): Flow<Event> = callbackFlow {
        val request = Request.Builder().url("wss://ws.oopz.cn/").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val body = JSONObject()
                    .put("person", session.uid)
                    .put("deviceId", deviceId)
                    .put("signature", session.signature)
                    .put("deviceName", deviceId)
                webSocket.send(
                    JSONObject()
                        .put("time", System.currentTimeMillis().toString())
                        .put("event", HANDSHAKE)
                        .put("body", body.toString())
                        .toString()
                )
                Log.i(TAG, "已发送握手 253")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val body = runCatching { JSONObject(json.optString("body")) }.getOrNull()
                val id = json.optInt("event", -1)
                trySend(Event(id, body))
                when (id) {
                    CHECK_RESULT -> {
                        val ok = body?.optBoolean("checkRes") == true
                        Log.i(TAG, "网关校验 checkRes=$ok")
                        if (!ok) channel.close(IllegalStateException("网关拒绝握手（签名无效或已过期）"))
                    }
                    READY -> Log.i(TAG, "网关就绪 serverId=${body?.optString("serverId")}")
                    MESSAGE -> Log.i(TAG, "收到消息事件")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "连接断开：${t.message}")
                channel.close(t)
            }
        })
        awaitClose { socket.close(1000, "bye") }
    }

    companion object {
        private const val TAG = "OopzWs"
        const val HANDSHAKE = 253
        const val READY = 1
        const val CHECK_RESULT = 21
        const val MESSAGE = 9

        /** 从网关事件里尽量捞出语音频道（字段随版本变化，宽松解析） */
        fun extractChannels(body: JSONObject?): List<OopzApi.Channel> {
            if (body == null) return emptyList()
            val out = mutableListOf<OopzApi.Channel>()

            fun walk(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        val id = value.optString("id")
                        val name = value.optString("name")
                        val type = value.optString("type")
                        if (id.length in 20..32 && name.isNotBlank() && type.uppercase().contains("VOICE")) {
                            out += OopzApi.Channel(id, name, type)
                        }
                        value.keys().forEach { walk(value.opt(it)) }
                    }
                    is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
            walk(body)
            return out.distinctBy { it.id }
        }
    }
}
