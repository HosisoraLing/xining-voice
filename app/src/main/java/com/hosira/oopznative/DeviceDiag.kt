package com.hosira.oopznative

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** 设备/音频诊断：出问题时用户截图给开发者最快。 */
object DeviceDiag {

    fun snapshot(context: Context, session: OopzApi.Session?): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sb = StringBuilder()

        sb.appendLine("机型：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine("小米系：${MiuiHelper.isMiui()}")
        sb.appendLine("电池白名单：${MiuiHelper.isIgnoringBatteryOptimizations(context)}")
        sb.appendLine()

        sb.appendLine("音频模式：${audioModeName(am.mode)}")
        sb.appendLine("SCO：开启=${am.isBluetoothScoOn} 可用=${am.isBluetoothScoAvailableOffCall}")
        sb.appendLine("扬声器外放：${am.isSpeakerphoneOn}")
        sb.appendLine("通话设备：${am.communicationDevice?.let { describe(it.type) } ?: "未指定"}")
        sb.appendLine("音量：语音=${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} 媒体=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}")
        sb.appendLine()

        sb.appendLine("可用输出设备：")
        am.availableCommunicationDevices.forEach { sb.appendLine("  · ${describe(it.type)} — ${it.productName}") }

        sb.appendLine("当前输入/输出硬件：")
        am.getDevices(AudioManager.GET_DEVICES_ALL).forEach { d ->
            val dir = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET ->
                    if (d.isSource) "输入" else "输出"
                else -> if (d.isSource) "输入" else "输出"
            }
            if (d.isSource || d.isSink) sb.appendLine("  · [$dir] ${describe(d.type)} — ${d.productName}")
        }
        sb.appendLine()

        val car = CarLinkHelper.carConnected(context)
        sb.appendLine("车机检测：${car?.let { "${it.name}（可拾音=${it.supportsMic}）" } ?: "未连接"}")
        CarLinkHelper.connectedDevices(context).forEach { sb.appendLine("  · 蓝牙：${it.name} 车机=${it.isCar}") }
        sb.appendLine()

        sb.appendLine("登录状态：${session?.let { "uid=${it.uid.take(8)}… 签名长度=${it.signature.length}" } ?: "未登录"}")
        sb.appendLine("持久化会话：${if (SessionStore.load(context) != null) "有" else "无"}")
        return sb.toString()
    }

    private fun audioModeName(mode: Int) = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL(媒体)"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION(通话)"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        else -> "MODE_$mode"
    }

    private fun describe(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙(通话)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙(A2DP)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙LE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB音频"
        AudioDeviceInfo.TYPE_TELEPHONY -> "通话线路"
        else -> "类型$type"
    }
}
