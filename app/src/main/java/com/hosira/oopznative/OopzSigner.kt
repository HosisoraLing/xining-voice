package com.hosira.oopznative

import android.content.Context
import android.util.Log
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * 复刻网页端的请求签名。
 *
 * 实测结论（用 WebCrypto 钩子抓到的待签报文 + 公钥验签确认）：
 *   message  = md5Hex( path[?query][+body] ) + 毫秒时间戳        （无分隔符，45 字节起）
 *   signature= RSA-PKCS1v15 + SHA-256(privateKey, message) → base64
 *
 * POST/PUT 的签名要把 body 一起算进 md5（只签 path 会被 401）。
 * 私钥来自网页端内置密钥（公开产物），不是任何人的私钥。
 */
object OopzSigner {

    private const val TAG = "OopzSigner"
    private var key: java.security.PrivateKey? = null
    var deviceId: String = UUID.randomUUID().toString()
        private set

    fun init(context: Context, deviceIdOverride: String? = null) {
        if (key != null) return
        val pem = context.assets.open("sign_key.pem").bufferedReader().readText()
        val body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "").replace("\r", "").trim()
        val der = Base64.getDecoder().decode(body)
        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        Log.i(TAG, "签名私钥已加载")
        if (!deviceIdOverride.isNullOrBlank()) deviceId = deviceIdOverride
    }

    fun setDeviceId(id: String) {
        if (id.isNotBlank()) deviceId = id
    }

    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 返回需要附加到请求上的鉴权头 */
    fun headers(pathWithQuery: String, body: String?, uid: String?, token: String?): Map<String, String> {
        val k = key ?: return emptyMap()
        val ts = System.currentTimeMillis().toString()
        val signedInput = pathWithQuery + (body ?: "")
        val message = md5Hex(signedInput) + ts
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(k)
        signer.update(message.toByteArray(Charsets.UTF_8))
        val sig = Base64.getEncoder().encodeToString(signer.sign())

        val headers = mutableMapOf(
            "Oopz-Sign" to sig,
            "Oopz-Time" to ts,
            "Oopz-Request-Id" to UUID.randomUUID().toString(),
            "Oopz-Device-Id" to deviceId,
            "Oopz-App-Version-Number" to "91507",
            "Oopz-Platform" to "android",
            "Oopz-Channel" to "Web",
            "Oopz-Web" to "true",
            "Accept" to "*/*",
            "Origin" to "https://web.oopz.cn",
            "Referer" to "https://web.oopz.cn/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
            "Content-Type" to "application/json;charset=utf-8",
        )
        if (!uid.isNullOrBlank()) headers["Oopz-Person"] = uid
        if (!token.isNullOrBlank()) headers["Oopz-Token"] = token
        return headers
    }

    fun isReady(): Boolean = key != null
}
