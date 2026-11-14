package com.hosira.oopznative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialExtractorTest {

    @Test
    fun `键名带 signature 的值优先`() {
        val dump = """{"LS_oopz__signature_abc":"SIGVALUE0123456789ABCDEFGHIJ","LS_other":"x"}"""
        val (uid, sig) = CredentialExtractor.extract(dump)!!
        assertEquals("SIGVALUE0123456789ABCDEFGHIJ", sig)
        assertEquals("", uid)
    }

    @Test
    fun `值本身是JSON时递归找token`() {
        val dump = """{"LS_oopz__user":"{\"uid\":\"3690998035123063\",\"token\":\"TK0123456789abcdefghijklmn\"}"}"""
        val (uid, sig) = CredentialExtractor.extract(dump)!!
        assertEquals("3690998035123063", uid)
        assertEquals("TK0123456789abcdefghijklmn", sig)
    }

    @Test
    fun `没有关键字时退化为高熵串`() {
        val dump = """{"LS_oopz__whatever":"abcdef0123456789abcdef0123456789"}"""
        val (_, sig) = CredentialExtractor.extract(dump)!!
        assertEquals("abcdef0123456789abcdef0123456789", sig)
    }

    @Test
    fun `cookie 里也能找到`() {
        val dump = """{"COOKIE":"a=1; SESSDATA=ABCDEF0123456789ABCDEF0123456789; b=2"}"""
        val (_, sig) = CredentialExtractor.extract(dump)!!
        assertEquals("ABCDEF0123456789ABCDEF0123456789", sig)
    }

    @Test
    fun `空字符串和坏JSON返回null`() {
        assertNull(CredentialExtractor.extract("{}"))
        assertNull(CredentialExtractor.extract("not-json"))
        assertNull(CredentialExtractor.extract("""{"a":"short"}"""))
    }
}
