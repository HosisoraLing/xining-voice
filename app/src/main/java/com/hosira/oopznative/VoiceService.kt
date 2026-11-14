package com.hosira.oopznative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager

/**
 * 语音保活前台服务 + 通知栏控制条。
 * 通知上常驻三个动作：麦克风开关 / 声音开关 / 退出语音。
 * 小米超级岛（焦点通知）没有公开 API：这里用 miui.focus.* 扩展字段尽力争取，
 * 拿不到时退化为普通常驻通知，功能不受影响。
 */
class VoiceService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        VoiceControls.init(this)
        VoiceControls.load(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        startForegroundWithType(buildNotification())
        instance = this
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oopz:voice").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "语音通话", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "语音连接期间保持运行，并提供麦克风/声音开关"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun action(index: Int, action: String, icon: Int, title: String): Notification.Action {
        val intent = Intent(this, VoiceActionReceiver::class.java).setAction(action)
        val pi = PendingIntent.getBroadcast(
            this, index, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pi).build()
    }

    internal fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val piOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)

        val micTitle = if (VoiceControls.micEnabled) "关闭麦克风" else "打开麦克风"
        val soundTitle = if (VoiceControls.soundEnabled) "静音对方" else "恢复声音"

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (VoiceControls.micEnabled) "语音通话中" else "语音通话中（已闭麦）")
            .setContentText("麦克风${if (VoiceControls.micEnabled) "开" else "关"} · 声音${if (VoiceControls.soundEnabled) "开" else "关"}")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(piOpen)
            .addAction(action(1, VoiceActionReceiver.ACTION_TOGGLE_MIC, android.R.drawable.ic_btn_speak_now, micTitle))
            .addAction(action(2, VoiceActionReceiver.ACTION_TOGGLE_SOUND, android.R.drawable.ic_lock_silent_mode, soundTitle))
            .addAction(action(3, VoiceActionReceiver.ACTION_EXIT, android.R.drawable.ic_menu_close_clear_cancel, "退出语音"))

        // 小米超级岛 / 焦点通知：非公开接口，尽力而为（失败自动退化）
        if (VoiceControls.superIslandEnabled) {
            runCatching {
                val extras = Bundle().apply {
                    putString("miui.focus.param", "{\"type\":\"voice\",\"title\":\"汐宁语音\",\"subTitle\":\"${if (VoiceControls.micEnabled) "麦克风开" else "已闭麦"}\"}")
                    putBoolean("miui.focus.show", true)
                    putInt("miui.focus.priority", 1)
                }
                builder.addExtras(extras)
            }
        }
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "oopz_voice"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }

        private var instance: VoiceService? = null

        /** 状态变化后原地刷新通知，不重启服务（保活链路不断） */
        fun refresh(context: Context) {
            val svc = instance
            if (svc == null) {
                start(context)
                return
            }
            runCatching { svc.notificationManager?.notify(NOTIFICATION_ID, svc.buildNotification()) }
        }
    }
}
