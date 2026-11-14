package com.hosira.oopznative

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlin.math.abs

/**
 * 车机互联识别（小米汽车 / 车机蓝牙）。
 * 判断依据：蓝牙设备类别为车载音响，或名称里带小米汽车常见字样。
 */
object CarLinkHelper {

    data class BtDevice(val name: String, val isCar: Boolean, val supportsMic: Boolean)

    private val carHints = listOf("su7", "yu7", "xiaomi", "小米", "car", "auto", "车载", "车机", "hud")

    fun connectedDevices(context: Context): List<BtDevice> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val adapter: BluetoothAdapter = manager.adapter ?: return emptyList()
        val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded.map { device ->
            val cls = runCatching { device.bluetoothClass }.getOrNull()
            val majorCar = cls?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO &&
                cls.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
            val name = device.name.orEmpty()
            val byName = carHints.any { name.lowercase().contains(it) }
            BtDevice(
                name = name.ifBlank { device.address },
                isCar = majorCar || byName,
                supportsMic = cls?.hasService(BluetoothClass.Service.CAPTURE) == true || majorCar
            )
        }
    }

    /** 当前是否有车机连着（用于界面提示 + 自动切换建议）。 */
    fun carConnected(context: Context): BtDevice? = connectedDevices(context).firstOrNull { it.isCar }
}
