package com.hosira.oopznative

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏 / 超级岛 上的按钮回调 */
class VoiceActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_MIC -> {
                VoiceControls.toggleMic()
                VoiceService.refresh(context)
            }
            ACTION_TOGGLE_SOUND -> {
                VoiceControls.toggleSound()
                VoiceService.refresh(context)
            }
            ACTION_EXIT -> {
                VoiceManager.leaveRoom()
                VoiceService.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_MIC = "com.hosira.oopznative.TOGGLE_MIC"
        const val ACTION_TOGGLE_SOUND = "com.hosira.oopznative.TOGGLE_SOUND"
        const val ACTION_EXIT = "com.hosira.oopznative.EXIT_VOICE"
    }
}
