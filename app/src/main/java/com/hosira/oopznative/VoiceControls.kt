package com.hosira.oopznative

import android.content.Context
import android.content.SharedPreferences

/** 语音控制状态（通知栏 / 超级岛 与界面共享） */
object VoiceControls {

    private const val PREF = "oopz_voice_prefs"
    private const val KEY_ISLAND = "super_island"

    var micEnabled: Boolean = true
        private set
    var soundEnabled: Boolean = true
        private set

    fun init(context: Context) {
        prefs(context)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var superIslandEnabled: Boolean
        get() {
            return islandFlag
        }
        set(value) {
            islandFlag = value
        }

    private var islandFlag: Boolean = true

    fun load(context: Context) {
        val p = prefs(context)
        islandFlag = p.getBoolean(KEY_ISLAND, true)
    }

    fun saveIsland(context: Context, enabled: Boolean) {
        islandFlag = enabled
        prefs(context).edit().putBoolean(KEY_ISLAND, enabled).apply()
    }

    fun toggleMic() {
        micEnabled = !micEnabled
        VoiceManager.setMicEnabled(micEnabled)
    }

    fun toggleSound() {
        soundEnabled = !soundEnabled
        VoiceManager.muteRemote(!soundEnabled)
    }
}
