package com.hosira.oopznative

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 小米互联互通 / 车机互联检测。
 * 小米汽车（SU7/YU7）的语音通道不一定走标准蓝牙 HFP/A2DP：
 * 互联互通服务可能把音频以 USB、BUS、REMOTE_SUBMIX 等形式暴露给应用层。
 * 这里不猜包名，按关键字扫已安装服务，并把所有音频设备类型如实列出。
 */
object XiaomiLinkHelper {

    private val keywords = listOf(
        "mi_connect", "miconnect", "interconnect", "carwith", "car_with",
        "hyperconnect", "xiaomi.car", "miui.car", "car.launcher", "autolink", "iccoa", "carlife"
    )

    data class LinkPackage(val pkg: String, val matched: String)

    /** 已安装的互联互通/车机相关包（Android 11+ 需 QUERY_ALL_PACKAGES） */
    fun installedLinks(context: Context): List<LinkPackage> {
        val pm = context.packageManager
        val packages = runCatching {
            pm.getInstalledPackages(PackageManager.GET_META_DATA).map { it.packageName }
        }.getOrElse { emptyList() }
        return packages.mapNotNull { pkg ->
            val hit = keywords.firstOrNull { pkg.lowercase().contains(it) }
            if (hit != null) LinkPackage(pkg, hit) else null
        }.distinctBy { it.pkg }
    }

    /** 互联互通/车机可能用来传音频的设备类型 */
    fun linkAudioDevices(context: Context): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_ALL).filter { d ->
            d.type in setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BUS,
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_DOCK,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )
        }
    }

    /** 看起来像车机通道的设备（优先给「手机麦+车媒体音」模式用） */
    fun preferredCarOutput(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val candidates = am.availableCommunicationDevices
        return candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUS }
            ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    fun describe(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙(通话 HFP)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙(媒体 A2DP)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙LE耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
        AudioDeviceInfo.TYPE_BUS -> "车机总线音频"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "远程混音(互联互通)"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> "底座"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路"
        else -> "类型$type"
    }
}
