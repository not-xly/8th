package com.xly.u_8th

import com.xly.u_8th.data.FlutterPrefsCodecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.Base64

class PrefsCodecsTest {

    private val prefix = FlutterPrefsCodecs.LIST_PREFIX

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
        assertEquals(listOf("a", "b"), FlutterPrefsCodecs.decodeStringList("$prefix![\"a\",\"b\"]"))
    }

    @Test
    fun `decodes a legacy java-serialized list preserving order`() {
        assertEquals(
            listOf("uno", "dos", "tres"),
            FlutterPrefsCodecs.decodeStringList(legacySerializedValue(listOf("uno", "dos", "tres"))),
        )
    }

    @Test
    fun `decodes json escapes`() {
        assertEquals(
            listOf("cañón \"cita\"", "a\nb"),
            FlutterPrefsCodecs.decodeStringList("$prefix![\"cañón \\\"cita\\\"\",\"a\\nb\"]"),
        )
    }

    @Test
    fun `returns null for garbage`() {
        assertNull(FlutterPrefsCodecs.decodeStringList(""))
        assertNull(FlutterPrefsCodecs.decodeStringList("garbage"))
        assertNull(FlutterPrefsCodecs.decodeStringList(prefix))
        assertNull(FlutterPrefsCodecs.decodeStringList("$prefix!no json"))
        assertNull(FlutterPrefsCodecs.decodeStringList("$prefix!###"))
        // Valid Base64 but not a serialized list.
        assertNull(
            FlutterPrefsCodecs.decodeStringList(
                "$prefix!" + Base64.getEncoder().encodeToString("hello".toByteArray()),
            )
        )
    }
}
