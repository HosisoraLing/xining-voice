package com.hosira.oopznative

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 简易文件日志：出事时可以把日志导出，比截图好定位。 */
object LogStore {

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "oopz-native.log")
        if (file!!.length() > 512 * 1024) file!!.delete()
        append("===== 启动 =====")
    }

    fun append(line: String) {
        runCatching {
            file?.appendText("${fmt.format(Date())} $line\n")
        }
    }

    fun export(context: Context): String {
        val dir = File(context.filesDir, "logs")
        val out = File(context.cacheDir, "oopz-native-log.txt")
        val all = dir.listFiles().orEmpty().sortedBy { it.name }
        out.writeText(all.joinToString("\n") { f -> "--- ${f.name} ---\n" + f.readText() })
        return out.absolutePath
    }
}
