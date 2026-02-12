package com.futaiii.sudodroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NodeInputValidatorTest {
    @Test
    fun normalizeHttpMaskPathRoot_returnsExpectedValues() {
        assertEquals("", NodeInputValidator.normalizeHttpMaskPathRoot(null))
        assertEquals("", NodeInputValidator.normalizeHttpMaskPathRoot("   "))
        assertEquals("api-root", NodeInputValidator.normalizeHttpMaskPathRoot(" /api-root/ "))
        assertEquals("", NodeInputValidator.normalizeHttpMaskPathRoot("a/b"))
        assertEquals("", NodeInputValidator.normalizeHttpMaskPathRoot("root?1"))
    }

    @Test
    fun requireHttpMaskPathRoot_throwsForInvalidInput() {
        val slashError = assertThrows(IllegalArgumentException::class.java) {
            NodeInputValidator.requireHttpMaskPathRoot("a/b")
        }
        assertEquals("HTTP path root must be a single segment (no '/')", slashError.message)

        val charError = assertThrows(IllegalArgumentException::class.java) {
            NodeInputValidator.requireHttpMaskPathRoot("a?b")
        }
        assertEquals(
            "HTTP path root may only contain letters, digits, '_' or '-'",
            charError.message
        )
    }

    @Test
    fun parseCustomTablePatterns_splitsAndFilters() {
        val parsed = NodeInputValidator.parseCustomTablePatterns("xppvvxvv,\n  xxppvvvv; xppvvxvv")
        assertEquals(listOf("xppvvxvv", "xxppvvvv", "xppvvxvv"), parsed)
    }

    @Test
    fun requireValidCustomTablePattern_validatesShapeAndCounts() {
        NodeInputValidator.requireValidCustomTablePattern("xppvvxvv")
        NodeInputValidator.requireValidCustomTablePattern("XPPVVXVV")

        assertThrows(IllegalArgumentException::class.java) {
            NodeInputValidator.requireValidCustomTablePattern("xppv")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NodeInputValidator.requireValidCustomTablePattern("xppvvxv1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NodeInputValidator.requireValidCustomTablePattern("xppppvvv")
        }
    }

    @Test
    fun requirePaddingPercentRange_rejectsOutOfRangeValues() {
        NodeInputValidator.requirePaddingPercentRange(0, 100)

        assertThrows(IllegalArgumentException::class.java) {
            NodeInputValidator.requirePaddingPercentRange(-1, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NodeInputValidator.requirePaddingPercentRange(10, 101)
        }
    }
}
