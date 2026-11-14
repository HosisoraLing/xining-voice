package com.hosira.oopznative

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.tencent.trtc.TRTCCloud
import com.tencent.trtc.TRTCCloudDef
import com.tencent.trtc.TRTCCloudListener

/**
 * TRTC 原生语音封装：
 * - 进房/退房（TRTC 内置 3A：AEC 回声消除、ANS 降噪、AGC 自动增益，跑在原生层）
 * - 输出设备手动切换（扬声器 / 听筒 / 有线 / 蓝牙），不锁死在蓝牙
 * - 麦克风开关、音量、远端音量
 */
object VoiceManager {

    private const val TAG = "VoiceManager"
    private var trtc: TRTCCloud? = null
    private var audioManager: AudioManager? = null

    var onEvent: ((String) -> Unit)? = null

    val isReady: Boolean get() = trtc != null

    /** 实时音量（0~100），给界面做麦克风电平条 */
    var localVolume: Int = 0
        private set
    var remoteVolume: Int = 0
        private set

    /** 车机互联时的取音/放音策略 */
    enum class CarMode(val label: String) {
        AUTO("自动（跟随系统）"),
        CAR_MIC("车端麦克风（HFP）"),
        PHONE_MIC_MEDIA("手机麦克风 + 车媒体音")
    }

    private var customMic: CustomMic? = null
    var carMode: CarMode = CarMode.AUTO
        private set

    /** true=语音优先（TRTC SPEECH 音质，降噪与回声消除最激进）；false=音乐优先 */
    var speechQuality: Boolean = true

    fun applyAudioQuality() {
        val cloud = trtc ?: return
        cloud.startLocalAudio(
            if (speechQuality) TRTCCloudDef.TRTC_AUDIO_QUALITY_SPEECH else TRTCCloudDef.TRTC_AUDIO_QUALITY_MUSIC
        )
        emit(if (speechQuality) "降噪：语音优先" else "降噪：音乐优先")
    }

    enum class Route(val label: String) {
        SPEAKER("扬声器"), EARPIECE("听筒"), WIRED("有线耳机"), BLUETOOTH("蓝牙"),
        CAR_LINK("小米互联互通/车机"), AUTO("系统自动")
    }

    fun init(context: Context) {
        if (trtc != null) return
        appContext = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        trtc = TRTCCloud.sharedInstance(context.applicationContext).also { cloud ->
            cloud.setListener(object : TRTCCloudListener() {
                override fun onEnterRoom(result: Long) = emit("已进入语音房间（$result）")
                override fun onExitRoom(reason: Int) = emit("已退出语音房间（$reason）")
                override fun onError(errCode: Int, errMsg: String?, extraInfo: android.os.Bundle?) =
                    emit("TRTC 错误 $errCode：${errMsg ?: ""}")
                override fun onUserVoiceVolume(volumes: ArrayList<com.tencent.trtc.TRTCCloudDef.TRTCVolumeInfo>?, total: Int) {
                    localVolume = volumes?.firstOrNull { it.userId.isNullOrEmpty() || it.userId == "" }?.volume ?: 0
                    remoteVolume = total
                }

                override fun onUserAudioAvailable(userId: String?, available: Boolean) {
                    if (available) emit("${userId ?: "有人"} 开麦了")
                }
            })
        }
    }

    private fun emit(msg: String) {
        Log.i(TAG, msg)
        onEvent?.invoke(msg)
    }

    /** sdkAppId / userId / userSig 由 Oopz 服务端下发（进语音频道时获取）。 */
    fun enterRoom(sdkAppId: Int, userId: String, userSig: String, roomId: String) {
        val cloud = trtc ?: return emit("TRTC 未初始化")
        val params = TRTCCloudDef.TRTCParams().apply {
            this.sdkAppId = sdkAppId
            this.userId = userId
            this.userSig = userSig
            this.roomId = roomId.toIntOrNull() ?: roomId.hashCode().and(0x7fffffff)
            this.role = TRTCCloudDef.TRTCRoleAudience
            this.streamId = ""
        }
        cloud.enterRoom(params, TRTCCloudDef.TRTC_APP_SCENE_VOICE_CHATROOM)
        cloud.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_SPEECH)
        runCatching {
            val params = TRTCCloudDef.TRTCAudioVolumeEvaluateParams().apply { interval = 300 }
            cloud.enableAudioVolumeEvaluation(true, params)
        }
        setRoute(Route.AUTO)
    }

    fun leaveRoom() {
        stopScoGuard()
        customMic?.stop(); customMic = null
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false
        audioManager?.mode = AudioManager.MODE_NORMAL
        trtc?.stopLocalAudio()
        trtc?.exitRoom()
    }

    /** 不上房也能测麦克风：直接开本地采集，电平会写进 localVolume */
    fun selfTestMic(): Boolean = runCatching {
        val cloud = trtc ?: return false
        cloud.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_SPEECH)
        val params = TRTCCloudDef.TRTCAudioVolumeEvaluateParams().apply { interval = 200 }
        cloud.enableAudioVolumeEvaluation(true, params)
        emit("麦克风自测已启动")
        true
    }.getOrElse {
        emit("麦克风自测失败：${it.message}")
        false
    }

    fun muteRemote(mute: Boolean) {
        trtc?.muteAllRemoteAudio(mute)
        emit(if (mute) "已静音对方声音" else "对方声音已恢复")
    }

    fun setMicEnabled(on: Boolean) {
        if (on) trtc?.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_SPEECH) else trtc?.stopLocalAudio()
        emit(if (on) "麦克风已开" else "麦克风已关")
    }

    /**
     * 输出设备切换。TRTC 的 setAudioRoute 只认「扬声器/听筒」两档，
     * 有线/蓝牙要交给 AudioManager 的通信设备（Android 12+ 用 setCommunicationDevice）。
     */
    fun setRoute(route: Route) {
        val cloud = trtc ?: return
        when (route) {
            Route.SPEAKER -> {
                cloud.setAudioRoute(TRTCCloudDef.TRTC_AUDIO_ROUTE_SPEAKER)
                communicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            }
            Route.EARPIECE -> {
                cloud.setAudioRoute(TRTCCloudDef.TRTC_AUDIO_ROUTE_EARPIECE)
                communicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            }
            Route.WIRED -> communicationDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            Route.CAR_LINK -> communicationDevice(
                AudioDeviceInfo.TYPE_BUS, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_REMOTE_SUBMIX
            )
            Route.BLUETOOTH -> communicationDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            Route.AUTO -> {
                cloud.setAudioRoute(TRTCCloudDef.TRTC_AUDIO_ROUTE_SPEAKER)
                clearCommunicationDevice()
            }
        }
        emit("输出设备：${route.label}")
    }

    private fun communicationDevice(vararg types: Int) {
        val am = audioManager ?: return
        val device = am.availableCommunicationDevices.firstOrNull { it.type in types } ?: return
        am.setCommunicationDevice(device)
    }

    private fun clearCommunicationDevice() {
        audioManager?.clearCommunicationDevice()
    }

    /**
     * 车机互联模式：
     * - CAR_MIC：走 HFP/SCO，车机麦 + 车机音响，TRTC 内置 3A 全开（最省性能）
     * - PHONE_MIC_MEDIA：关掉 SCO，手机麦采集（系统 AEC/NS），输出走媒体流 → 车机 A2DP 音响
     */
    private var appContext: android.content.Context? = null

    fun applyCarMode(mode: CarMode) {
        carMode = mode
        val cloud = trtc ?: return
        val am = audioManager
        when (mode) {
            CarMode.CAR_MIC -> {
                stopScoGuard()
                customMic?.stop(); customMic = null
                am?.mode = AudioManager.MODE_IN_COMMUNICATION
                cloud.getDeviceManager().setSystemVolumeType(
                    com.tencent.liteav.device.TXDeviceManager.TXSystemVolumeType.TXSystemVolumeTypeVOIP
                )
                cloud.setAudioRoute(TRTCCloudDef.TRTC_AUDIO_ROUTE_BLUETOOTH_HEADSET)
                emit("车机模式：车端麦克风 + 车机音响（HFP）")
            }
            CarMode.PHONE_MIC_MEDIA -> {
                cloud.setAudioRoute(TRTCCloudDef.TRTC_AUDIO_ROUTE_SPEAKER)
                // 媒体音量类型 = 走 A2DP/媒体流，不会被拉到 SCO
                cloud.getDeviceManager().setSystemVolumeType(
                    com.tencent.liteav.device.TXDeviceManager.TXSystemVolumeType.TXSystemVolumeTypeMedia
                )
                am?.stopBluetoothSco()
                am?.isBluetoothScoOn = false
                am?.mode = AudioManager.MODE_NORMAL
                if (customMic == null) customMic = CustomMic(cloud)
                customMic?.start()
                startScoGuard()
                emit("车机模式：手机麦克风 + 车媒体音（A2DP）")
            }
            CarMode.AUTO -> {
                stopScoGuard()
                customMic?.stop(); customMic = null
                am?.mode = AudioManager.MODE_IN_COMMUNICATION
                cloud.getDeviceManager().setSystemVolumeType(
                    com.tencent.liteav.device.TXDeviceManager.TXSystemVolumeType.TXSystemVolumeTypeAuto
                )
                emit("车机模式：自动")
            }
        }
    }

    private var scoGuard: android.os.Handler? = null
    private var scoGuardTask: Runnable? = null

    /** 模式③ 下车机常会自己把 HFP 抢回来，这里每 3 秒压一次。 */
    private fun startScoGuard() {
        stopScoGuard()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val task = object : Runnable {
            override fun run() {
                val am = audioManager
                if (am != null && (am.isBluetoothScoOn || am.mode == AudioManager.MODE_IN_COMMUNICATION)) {
                    runCatching {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                        if (carMode == CarMode.PHONE_MIC_MEDIA) am.mode = AudioManager.MODE_NORMAL
                    }
                }
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(task, 3000)
        scoGuard = handler
        scoGuardTask = task
    }

    private fun stopScoGuard() {
        scoGuardTask?.let { scoGuard?.removeCallbacks(it) }
        scoGuard = null
        scoGuardTask = null
    }

    fun availableRouteLabels(context: Context): List<String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.availableCommunicationDevices.map { describe(it.type) }.distinct()
    }

    private fun describe(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙耳机"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频"
        else -> "设备$type"
    }

    fun destroy() {
        trtc?.let { TRTCCloud.destroySharedInstance() }
        trtc = null
    }
}
