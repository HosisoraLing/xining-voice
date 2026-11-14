package com.hosira.oopznative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `保存后能读回`() {
        SessionStore.save(context, OopzApi.Session("uid-1", "sig-abcdefghijklmnop", "白饭"))
        val loaded = SessionStore.load(context)
        assertNotNull(loaded)
        assertEquals("uid-1", loaded!!.uid)
        assertEquals("sig-abcdefghijklmnop", loaded.signature)
        assertEquals("白饭", loaded.name)
    }

    @Test
    fun `清空后读不到`() {
        SessionStore.save(context, OopzApi.Session("u", "s", "n"))
        SessionStore.clear(context)
        assertNull(SessionStore.load(context))
    }

    @Test
    fun `设备ID多次调用保持一致`() {
        val first = SessionStore.deviceId(context)
        val second = SessionStore.deviceId(context)
        assertEquals(first, second)
        assertEquals(16, first.length)
    }
}
