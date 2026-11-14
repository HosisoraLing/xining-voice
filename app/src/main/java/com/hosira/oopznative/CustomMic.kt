package com.hosira.oopznative

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.tencent.trtc.TRTCCloud
import com.tencent.trtc.TRTCCloudDef
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自定义采集：用手机自带麦克风录 48k 单声道 PCM 推给 TRTC。
 * 目的：车机互联时不想用车端麦克风（SCO 会抢麦），就关掉 SCO，用手机拾音。
 * 同时开系统级 AEC/NS（平台实现，开销小）。
 */
class CustomMic(private val trtc: TRTCCloud) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running.get()) return
        val rate = 48000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuf * 2, rate / 5 * 2) // 200ms 缓冲，抗抖动
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        // 明确指定手机内置麦克风，避免被蓝牙 SCO 抢走
        val builtIn = rec.audioSource
        record = rec
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        running.set(true)
        rec.startRecording()
        trtc.enableCustomAudioCapture(true)
        Log.i("CustomMic", "手机麦克风采集启动 source=$builtIn aec=${aec != null} ns=${ns != null}")

        thread = Thread {
            val buf = ByteArray(rate / 50 * 2) // 20ms
            while (running.get()) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    val frame = TRTCCloudDef.TRTCAudioFrame().apply {
                        data = buf.copyOf(read)
                        sampleRate = rate
                        channel = 1
                        timestamp = 0
                    }
                    trtc.sendCustomAudioData(frame)
                }
            }
        }.apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { thread?.join(500) }
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        aec = null; ns = null
        trtc.enableCustomAudioCapture(false)
        Log.i("CustomMic", "手机麦克风采集停止")
    }
}
