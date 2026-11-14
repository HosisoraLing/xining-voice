package com.hosira.oopznative

import android.content.Context
import android.content.SharedPreferences

/** 登录状态持久化：签名存本地，下次启动直接进域列表。 */
object SessionStore {

    private const val PREF = "oopz_session"
    private const val KEY_UID = "uid"
    private const val KEY_SIG = "signature"
    private const val KEY_NAME = "name"
    private const val KEY_DEVICE = "deviceId"

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(context: Context, session: OopzApi.Session) {
        prefs(context).edit()
            .putString(KEY_UID, session.uid)
            .putString(KEY_SIG, session.signature)
            .putString(KEY_NAME, session.name)
            .apply()
    }

    fun load(context: Context): OopzApi.Session? {
        val p = prefs(context)
        val uid = p.getString(KEY_UID, "").orEmpty()
        val sig = p.getString(KEY_SIG, "").orEmpty()
        if (sig.isBlank()) return null
        return OopzApi.Session(uid, sig, p.getString(KEY_NAME, "").orEmpty())
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    /** 网页登录时抓到的存储原文，出错时贴给开发者最快 */
    var lastWebDump: String = ""
        private set

    fun rememberDump(dump: String) {
        lastWebDump = dump
    }

    /** 设备 ID 固定下来，避免每次登录都被当成新设备 */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_DEVICE, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val fresh = OopzApi.deviceId()
        p.edit().putString(KEY_DEVICE, fresh).apply()
        return fresh
    }
}
