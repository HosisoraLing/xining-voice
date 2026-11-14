package com.hosira.oopznative

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * 只用系统 WebView 做「登录」这一步。
 * Oopz 的 REST 接口要客户端签名，签名算法在 Dart 编译产物里，
 * 与其硬逆不如借它自己的登录流程拿 signature，之后全程原生（不打包任何浏览器内核）。
 */
class WebLoginActivity : Activity() {

    private lateinit var web: WebView
    private var finished = false
    private var lastDump: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(FrameLayout(this).apply { addView(web) })
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(Bridge(), "oopzBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                startPolling()
            }
        }
        web.loadUrl("https://web.oopz.cn/")
    }

    private fun startPolling() {
        web.postDelayed(object : Runnable {
            override fun run() {
                if (finished || isFinishing) return
                web.evaluateJavascript(DUMP_JS) { raw ->
                    val json = unescape(raw) ?: return@evaluateJavascript
                    lastDump = json
                    pickSession(json)?.let { (uid, sig) ->
                        finished = true
                        setResult(
                            RESULT_OK,
                            Intent().putExtra("uid", uid).putExtra("signature", sig).putExtra("dump", lastDump)
                        )
                        finish()
                    }
                }
                web.postDelayed(this, 2000)
            }
        }, 2500)
    }

    /** JS 结果是被 JSON 编码的字符串，先还原 */
    private fun unescape(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val trimmed = raw.trim()
            if (trimmed.startsWith("\"")) {
                org.json.JSONTokener(trimmed).nextValue() as? String
            } else trimmed
        }.getOrNull()
    }

    private fun pickSession(dump: String): Pair<String, String>? = CredentialExtractor.extract(dump)

    inner class Bridge {
        @JavascriptInterface
        fun onReady() = Unit
    }

    override fun onDestroy() {
        runCatching { web.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val DUMP_JS = """
            (function(){
              var out={};
              try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);out['LS_'+k]=localStorage.getItem(k);}}catch(e){}
              try{for(var i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);out['SS_'+k]=sessionStorage.getItem(k);}}catch(e){}
              try{out['COOKIE']=document.cookie;}catch(e){}
              return JSON.stringify(out);
            })()
        """
    }
}
