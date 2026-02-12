package com.futaiii.sudodroid.protocol

import com.futaiii.sudodroid.data.NodeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShortLinkCodecTest {
    @Test
    fun fromLink_acceptsCaseInsensitiveScheme() {
        val node = NodeConfig(
            host = "example.com",
            port = 443,
            key = "test-key",
            customTable = "xppvvxvv",
            customTables = listOf("xppvvxvv")
        )
        val link = ShortLinkCodec.toLink(node).replace("sudoku://", "SUDOKU://")

        val decoded = ShortLinkCodec.fromLink(link)

        assertEquals("example.com", decoded.host)
        assertEquals(443, decoded.port)
        assertEquals("test-key", decoded.key)
        assertEquals(listOf("xppvvxvv"), decoded.customTables)
    }

    @Test
    fun fromLink_rejectsEmptyPayload() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ShortLinkCodec.fromLink("   ")
        }
        assertEquals("short link is empty", error.message)
    }

    @Test
    fun fromLink_rejectsOutOfRangeServerPort() {
        val payload = """{"h":"example.com","p":70000,"k":"k"}"""
        val link = "sudoku://${payload.toBase64Url()}"

        val error = assertThrows(IllegalArgumentException::class.java) {
            ShortLinkCodec.fromLink(link)
        }
        assertEquals("short link has invalid server port", error.message)
    }

    @Test
    fun fromLink_rejectsOutOfRangeLocalPort() {
        val payload = """{"h":"example.com","p":443,"m":70000,"k":"k"}"""
        val link = "sudoku://${payload.toBase64Url()}"

        val error = assertThrows(IllegalArgumentException::class.java) {
            ShortLinkCodec.fromLink(link)
        }
        assertEquals("short link has invalid local proxy port", error.message)
    }

    @Test
    fun fromLink_rejectsInvalidCustomTable() {
        val payload = """{"h":"example.com","p":443,"k":"k","t":"xppv"}"""
        val link = "sudoku://${payload.toBase64Url()}"

        val error = assertThrows(IllegalArgumentException::class.java) {
            ShortLinkCodec.fromLink(link)
        }
        assertTrue(error.message?.contains("Custom table") == true)
    }
}

private fun String.toBase64Url(): String {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(toByteArray())
}
