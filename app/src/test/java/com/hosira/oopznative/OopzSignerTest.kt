package com.hosira.oopznative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** 用抓包得到的真实样本验证签名实现（公钥来自同一次逆向）。 */
@RunWith(RobolectricTestRunner::class)
class OopzSignerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        OopzSigner.init(context, "7fdda227-6ad6-4581-a1c1-5e126a3a5969")
    }

    @Test
    fun `签名头齐全`() {
        val h = OopzSigner.headers("/health", null, null, null)
        assertTrue(h.containsKey("Oopz-Sign"))
        assertEquals("7fdda227-6ad6-4581-a1c1-5e126a3a5969", h["Oopz-Device-Id"])
        assertEquals("91507", h["Oopz-App-Version-Number"])
        assertEquals("true", h["Oopz-Web"])
        // 时间戳应是 13 位
        assertEquals(13, h["Oopz-Time"]!!.length)
    }

    @Test
    fun `签名可被公钥验证且报文格式正确`() {
        val path = "/general/v2/curTime"
        val h = OopzSigner.headers(path, null, null, null)
        val sig = Base64.getDecoder().decode(h["Oopz-Sign"])
        val ts = h["Oopz-Time"]!!
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        val message = (md5 + ts).toByteArray()

        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(TEST_PUBLIC_SPKI)))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pub)
        verifier.update(message)
        assertTrue("签名应能被公钥验证", verifier.verify(sig))
    }

    companion object {
        // 从内置私钥导出的公钥（SubjectPublicKeyInfo base64）
        private const val TEST_PUBLIC_SPKI = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAv3W0sdJGoD0ZoQXWhMRezz06+AhHNMDRP6WUF42cCCImhq7ioMOHz0v7LXjWS5i6P9tt3FHFte1c2v4Yb4WPW8j+LKavWhBnVIw2Bx8gcp5VGE3ZG3GJtiHgkJ0PfbmTj7y/2emySFVF3wqdiPvIc7JSu2iIzYH9eWHx13YujD4nPupRQmJN/ESvjil5auI2cR7zOIoFcSXAsFPnT9Q9GPQ/53uFiIo0iM/R08fQDXN2HkeHMTffJaKPc3NdXEeu+5onZN2ekJ9AbB3kOPMLc/JdS4GFBYmkClEuy27W5H2DhwQw0TIuB0MU2ZsYd+e42ngTUXqeBYo9nVKrZHNZUQIDAQAB"
    }
}
