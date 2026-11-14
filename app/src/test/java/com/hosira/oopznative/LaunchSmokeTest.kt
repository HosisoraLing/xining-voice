package com.hosira.oopznative

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/** 冒烟测试：不连网、不碰 TRTC 原生库，只验证组件声明与主题能正常解析。 */
@RunWith(RobolectricTestRunner::class)
class LaunchSmokeTest {

    @Test
    fun `MainActivity 类可加载且声明为启动页`() {
        // Compose 首屏渲染需要 GPU，Robolectric 里最多只能验证类可加载 + 清单声明
        val cls = Class.forName("com.hosira.oopznative.MainActivity")
        assertNotNull(cls)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        val activities = pm.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty()
        assertEquals(true, activities.any { it.name.endsWith("MainActivity") })
    }

    @Test
    fun `WebLoginActivity 能实例化`() {
        val activity = Robolectric.buildActivity(WebLoginActivity::class.java).create().get()
        assertNotNull(activity)
    }

    @Test
    fun `清单里声明了语音服务与按钮接收器`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        val services = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES).services.orEmpty()
        val receivers = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_RECEIVERS).receivers.orEmpty()
        assertEquals(true, services.any { it.name.endsWith("VoiceService") })
        assertEquals(true, receivers.any { it.name.endsWith("VoiceActionReceiver") })
    }
}
