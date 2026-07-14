package org.graphiks.kextract.kotlin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class KotlinNameManglerTest {

    @Test
    fun `mangles reserved hard keywords`() {
        assertEquals("fun_", KotlinNameMangler.mangle("fun"))
        assertEquals("class_", KotlinNameMangler.mangle("class"))
    }

    @Test
    fun `mangles the reserved future keyword typeof`() {
        assertEquals("typeof_", KotlinNameMangler.mangle("typeof"))
    }

    @Test
    fun `replaces invalid characters`() {
        assertEquals("foo_bar", KotlinNameMangler.mangle("foo\$bar"))
    }

    @Test
    fun `handles leading digits`() {
        assertEquals("_foo", KotlinNameMangler.mangle("1foo"))
    }

    @Test
    fun `leaves ordinary identifiers untouched`() {
        assertEquals("NSString", KotlinNameMangler.mangle("NSString"))
        assertEquals("CFRunLoopRef", KotlinNameMangler.mangle("CFRunLoopRef"))
    }

    // Regression: a C typedef named `Boolean` (CoreFoundation `typedef unsigned char Boolean`)
    // must NOT produce a top-level `typealias Boolean = Byte`, which shadows kotlin.Boolean
    // across the whole package and breaks every `is Boolean` / Boolean argument at runtime.
    @Test
    fun `mangles Kotlin builtin type names to avoid shadowing`() {
        assertNotEquals("Boolean", KotlinNameMangler.mangle("Boolean"))
        assertNotEquals("Byte", KotlinNameMangler.mangle("Byte"))
        assertNotEquals("Int", KotlinNameMangler.mangle("Int"))
        assertNotEquals("Short", KotlinNameMangler.mangle("Short"))
        assertNotEquals("Long", KotlinNameMangler.mangle("Long"))
        assertNotEquals("Float", KotlinNameMangler.mangle("Float"))
        assertNotEquals("Double", KotlinNameMangler.mangle("Double"))
        assertNotEquals("Char", KotlinNameMangler.mangle("Char"))
        assertNotEquals("String", KotlinNameMangler.mangle("String"))
        assertNotEquals("Unit", KotlinNameMangler.mangle("Unit"))
    }

    @Test
    fun `mangles Boolean to Boolean underscore consistently`() {
        assertEquals("Boolean_", KotlinNameMangler.mangle("Boolean"))
    }
}
