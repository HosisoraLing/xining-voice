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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Oopz 网关（wss://ws.oopz.cn/）。
 * 握手：event 253，body 里带 person/deviceId/signature/deviceName。
 * 频道/域的实时数据都从这个连接推下来；REST 只用来拿静态信息。
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
                val frame = JSONObject()
                    .put("time", System.currentTimeMillis().toString())
                    .put("event", 253)
                    .put("body", body.toString())
                webSocket.send(frame.toString())
                Log.i("OopzWs", "已发送握手 253")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val bodyText = json.optString("body")
                val body = runCatching { JSONObject(bodyText) }.getOrNull()
                trySend(Event(json.optInt("event", -1), body))
                when (json.optInt("event")) {
                    253 -> Unit
                    1 -> Log.i("OopzWs", "网关就绪：${body?.optString("serverId")}")
                    9 -> Log.i("OopzWs", "收到消息事件")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("OopzWs", "连接断开：${t.message}")
                channel.close(t)
            }
        })
        awaitClose { socket.close(1000, "bye") }
    }

    companion object {
        /** 从网关事件里尽量捞出频道（字段随版本变化，做成宽松解析）。 */
        fun extractChannels(body: JSONObject?): List<OopzApi.Channel> {
            if (body == null) return emptyList()
            val out = mutableListOf<OopzApi.Channel>()
            fun walk(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        val id = value.optString("id")
                        val name = value.optString("name")
                        val type = value.optString("type")
                        if (id.length in 20..30 && name.isNotBlank() && type.uppercase().contains("VOICE")) {
                            out += OopzApi.Channel(id, name, type)
                        }
                        value.keys().forEach { walk(value.opt(it)) }
                    }
                    is org.json.JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
            walk(body)
            return out.distinctBy { it.id }
        }
    }
}
