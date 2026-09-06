package com.xly.u_8th

import com.xly.u_8th.data.LegacyPrefsCodecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.Base64

class PrefsCodecsTest {

    private val prefix = LegacyPrefsCodecs.LIST_PREFIX

    /** Builds the legacy Dart value: prefix + "!" + Base64(serialized ArrayList). */
    private fun legacySerializedValue(items: List<String>): String {
        val bytes = ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { oos ->
                oos.writeObject(ArrayList(items))
            }
            bos.toByteArray()
        }
        return prefix + "!" + Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `decodes the newer json format preserving order`() {
        assertEquals(listOf("a", "b"), LegacyPrefsCodecs.decodeStringList("$prefix![\"a\",\"b\"]"))
    }

    @Test
    fun `decodes a legacy java-serialized list preserving order`() {
        assertEquals(
            listOf("uno", "dos", "tres"),
            LegacyPrefsCodecs.decodeStringList(legacySerializedValue(listOf("uno", "dos", "tres"))),
        )
    }

    @Test
    fun `decodes json escapes`() {
        assertEquals(
            listOf("cañón \"cita\"", "a\nb"),
            LegacyPrefsCodecs.decodeStringList("$prefix![\"cañón \\\"cita\\\"\",\"a\\nb\"]"),
        )
    }

    @Test
    fun `returns null for garbage`() {
        assertNull(LegacyPrefsCodecs.decodeStringList(""))
        assertNull(LegacyPrefsCodecs.decodeStringList("garbage"))
        assertNull(LegacyPrefsCodecs.decodeStringList(prefix))
        assertNull(LegacyPrefsCodecs.decodeStringList("$prefix!no json"))
        assertNull(LegacyPrefsCodecs.decodeStringList("$prefix!###"))
        // Valid Base64 but not a serialized list.
        assertNull(
            LegacyPrefsCodecs.decodeStringList(
                "$prefix!" + Base64.getEncoder().encodeToString("hello".toByteArray()),
            )
        )
    }
}
