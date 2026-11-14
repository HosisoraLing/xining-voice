package com.hosira.oopznative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtcParserTest {

    @Test
    fun `扁平字段能解析`() {
        val json = """{"sdkAppId":1400123456,"userId":"u_1001","userSig":"sig-abcdef","roomId":"88123"}"""
        val c = RtcParser.parse(json)!!
        assertEquals(1400123456, c.sdkAppId)
        assertEquals("u_1001", c.userId)
        assertEquals("sig-abcdef", c.userSig)
        assertEquals("88123", c.roomId)
    }

    @Test
    fun `嵌套与下划线字段能解析`() {
        val json = """{"status":true,"data":{"rtc":{"sdk_app_id":"1400999","user_sig":"zzz","user_id":"x","str_room_id":42}}}"""
        val c = RtcParser.parse(json)!!
        assertEquals(1400999, c.sdkAppId)
        assertEquals("zzz", c.userSig)
        assertEquals("42", c.roomId)
    }

    @Test
    fun `数字房间号与data数组都能解析`() {
        val json = """{"data":[{"sdkAppId":1400001,"userSig":"s","roomId":777}]}"""
        val c = RtcParser.parse(json)!!
        assertEquals(1400001, c.sdkAppId)
        assertEquals("777", c.roomId)
    }

    @Test
    fun `缺字段返回null`() {
        assertNull(RtcParser.parse("""{"status":true,"data":{}}"""))
        assertNull(RtcParser.parse("not json"))
    }
}
