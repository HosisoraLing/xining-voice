package com.hosira.oopznative

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Oopz REST 客户端。接口来自社区文档（DeeChael/oopz-api-docs）与实测。 */
object OopzApi {

    // 官方网页端（2026-11 抓包）用的是 gateway-n.oopz.cn；老文档里的 api.oopz.cn 已解析不到
    private const val BASE = "https://gateway-n.oopz.cn"
    private const val BASE_ALT = "https://gateway.oopz.cn"   // 老网关，做兜底
    private const val APP_VERSION = "0.20.99"
    private const val APP_VERSION_NUM = "20099"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 诊断日志：最近几次请求的状态码和响应片段，界面上可复制给开发者对齐字段 */
    val debugLog = mutableListOf<String>()

    private fun record(tag: String, code: Int, body: String) {
        val line = "$tag → HTTP $code\n${body.take(300)}"
        synchronized(debugLog) {
            debugLog += line
            while (debugLog.size > 6) debugLog.removeAt(0)
        }
    }

    data class Session(val uid: String, val signature: String, val name: String)

    /** 密码登录；SMSCODE 走同样的入口，只是 code 是短信码。 */
    suspend fun login(phone: String, code: String, smsCode: Boolean, deviceId: String): Session =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("auto", true)
                .put("code", code)
                .put("loginType", if (smsCode) "SMSCODE" else "PASSWORD")
                .put("phone", phone)
                .put("autoRegister", false)
                .put("deviceId", deviceId)
                .put("deviceRam", "8")
                .put("deviceProcessor", "android")
                .put("loggedIn", deviceId)
                .put("osEdition", "android")
                .put("osVersion", "14")
                .put("resolution", "1080x2400")
                .put("graphics", "")
                .put("clientVersion", APP_VERSION)
            fun call(path: String): Pair<Int, String> {
                val req = Request.Builder()
                    .url("$BASE$path")
                    .post(body.toString().toRequestBody(jsonType))
                    .header("Oopz-App-Version", APP_VERSION)
                    .header("Oopz-App-Version-Number", APP_VERSION_NUM)
                    .header("Oopz-Device-Id", deviceId)
                    .header("Oopz-Time", System.currentTimeMillis().toString())
                    .build()
                val resp = http.newCall(req).execute()
                return resp.code to resp.body?.string().orEmpty()
            }
            var (code, text) = call("/client/v1/login/v2/login")
            record("登录 v2", code, text)
            if (code != 200 || !text.contains("\"status\"")) {
                val (code2, text2) = call("/client/v1/login/v1/login")
                record("登录 v1", code2, text2)
                code = code2; text = text2
            }
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("登录响应解析失败：${text.take(120)}")
            }
            if (code == 401) throw IllegalStateException("接口返回 401：Oopz 要求客户端签名，请改用「网页登录」")
            if (code == 428) throw IllegalStateException("接口返回 428：被风控拦了，稍后再试或改用「网页登录」")
            if (!root.optBoolean("status") || root.optString("code") != "200") {
                throw IllegalStateException(root.optString("message").ifBlank { "登录失败（${root.optString("code")}）" })
            }
            val data = root.optJSONObject("data") ?: throw IllegalStateException("登录响应缺少 data")
            val sig = data.optString("signature")
            if (sig.isBlank()) throw IllegalStateException("登录响应缺少 signature")
            Session(data.optString("uid"), sig, data.optString("name"))
        }

    fun deviceId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun authedRequest(path: String, session: Session): Request =
        Request.Builder()
            .url("$BASE$path")
            .header("Oopz-Signature", session.signature)
            .header("Oopz-Sign", session.signature)
            .header("Oopz-Person", session.uid)
            .header("Oopz-Device-Id", deviceId())
            .header("Oopz-Time", System.currentTimeMillis().toString())
            .header("Oopz-App-Version", APP_VERSION)
            .header("Oopz-App-Version-Number", APP_VERSION_NUM)
            .get()
            .build()

    data class Area(val id: String, val name: String, val avatar: String)

    suspend fun subscribedAreas(session: Session): List<Area> = withContext(Dispatchers.IO) {
        var lastError: String = "未知错误"
        for (base in listOf(BASE, BASE_ALT)) {
            val url = "$base/client/v1/area/v1/home/v1/subscribed"
            val req = Request.Builder()
                .url(url)
                .header("Oopz-Signature", session.signature)
                .header("Oopz-Sign", session.signature)
                .header("Oopz-Person", session.uid)
                .header("Oopz-Device-Id", deviceId())
                .header("Oopz-Time", System.currentTimeMillis().toString())
                .header("Oopz-App-Version", APP_VERSION)
                .header("Oopz-App-Version-Number", APP_VERSION_NUM)
                .get()
                .build()
            val resp = http.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            record("域列表 ${base.substringAfter("//")}", resp.code, text)

            if (resp.code == 401) {
                lastError = "401：签名未被接受（签名可能过期或提取有误）"
                continue
            }
            if (text.isBlank()) {
                lastError = "HTTP ${resp.code}，响应为空"
                continue
            }
            val root = runCatching { JSONObject(text) }.getOrNull()
                ?: run { lastError = "响应不是 JSON：${text.take(80)}"; null } ?: continue
            val arr = root.optJSONArray("data") ?: run {
                lastError = "响应缺少 data：${text.take(120)}"
                null
            } ?: continue
            val out = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Area(o.optString("id"), o.optString("name"), o.optString("avatar")))
                }
            }
            return@withContext out
        }
        throw IllegalStateException(lastError)
    }

    data class Channel(val id: String, val name: String, val type: String)

    /** 频道列表：/client/v1/area/v1/detail/v1/channels?area=<areaId> */
    suspend fun channels(session: Session, areaId: String): List<Channel> = withContext(Dispatchers.IO) {
        val resp = http.newCall(
            authedRequest("/client/v1/area/v1/detail/v1/channels?area=$areaId", session)
        ).execute()
        val text = resp.body?.string().orEmpty()
        record("频道列表", resp.code, text)
        if (text.isBlank()) return@withContext emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext emptyList()
        val data = root.opt("data")
        val out = mutableListOf<Channel>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val id = v.optString("id")
                    val name = v.optString("name")
                    if (id.length >= 20 && name.isNotBlank()) {
                        out += Channel(id, name, v.optString("type"))
                    }
                    v.keys().forEach { walk(v.opt(it)) }
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(data)
        out.distinctBy { it.id }
    }

    /** 进语音频道后拿到的 TRTC 参数（字段名随版本变化，用宽松匹配）。 */
    data class RtcCredentials(
        val sdkAppId: Int,
        val userId: String,
        val userSig: String,
        val roomId: String
    )

    private var lastRtcRaw: String = ""

    /** 调 /channel/enter，再把响应里任何像 TRTC 凭证的字段捞出来。 */
    suspend fun enterVoiceChannel(session: Session, areaId: String, channelId: String): RtcCredentials? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("type", "VOICE")
                .put("area", areaId)
                .put("channel", channelId)
                .put("fromChannel", "")
                .put("fromArea", "")
                .put("password", "")
                .put("sign", 0)
                .put("pid", -1)
            val req = Request.Builder()
                .url("$BASE/client/v1/channel/enter")
                .post(payload.toString().toRequestBody(jsonType))
                .header("Oopz-Signature", session.signature)
                .header("Oopz-Sign", session.signature)
                .header("Oopz-Person", session.uid)
                .header("Oopz-Device-Id", deviceId())
                .header("Oopz-Time", System.currentTimeMillis().toString())
                .header("Oopz-App-Version", APP_VERSION)
                .header("Oopz-App-Version-Number", APP_VERSION_NUM)
                .build()
            val resp2 = http.newCall(req).execute()
            val text = resp2.body?.string().orEmpty()
            record("进频道", resp2.code, text)
            lastRtcRaw = text
            parseRtc(text)
        }

    private fun parseRtc(text: String): RtcCredentials? =
        RtcParser.parse(text)?.let { RtcCredentials(it.sdkAppId, it.userId, it.userSig, it.roomId) }

    /** 备用路径：部分版本的进频道挂在 /rtc/v1 基址下 */
    suspend fun enterVoiceChannelFallback(session: Session, areaId: String, channelId: String): RtcCredentials? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("type", "VOICE").put("area", areaId).put("channel", channelId)
                .put("fromChannel", "").put("fromArea", "").put("password", "")
                .put("sign", 0).put("pid", -1)
            val req = Request.Builder()
                .url("$BASE/rtc/v1/channel/enter")
                .post(payload.toString().toRequestBody(jsonType))
                .header("Oopz-Signature", session.signature)
                .header("Oopz-Sign", session.signature)
                .header("Oopz-Person", session.uid)
                .header("Oopz-Device-Id", deviceId())
                .header("Oopz-Time", System.currentTimeMillis().toString())
                .build()
            val resp = http.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            record("进频道(rtc 基址)", resp.code, text)
            parseRtc(text)
        }

    fun rtcDebugDump(): String = lastRtcRaw.take(400)
}
