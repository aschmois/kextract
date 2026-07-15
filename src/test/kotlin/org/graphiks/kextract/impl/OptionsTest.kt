package org.graphiks.kextract.pipeline

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptionsTest {

    @Test
    fun `Options has correct defaults`() {
        val options = Options()
        assertEquals("", options.targetPackage)
        assertEquals(".", options.outputDir)
        assertNull(options.jvmNativeResourcesDir)
        assertEquals(false, options.useSystemLoadLibrary)
        assertTrue(options.clangArgs.isEmpty())
        assertTrue(options.libraries.isEmpty())
        assertNull(options.sharedClassName)
    }

    @Test
    fun `Options constructor sets all properties`() {
        val lib = Options.Library.parse("mylib")
        val options = Options(
            targetPackage      = "org.test",
            outputDir          = "/output",
            jvmNativeResourcesDir = "/resources",
            useSystemLoadLibrary = true,
            clangArgs          = listOf("-I/include"),
            libraries          = listOf(lib),
            sharedClassName    = "Symbols"
        )

        assertEquals("org.test", options.targetPackage)
        assertEquals("/output", options.outputDir)
        assertEquals("/resources", options.jvmNativeResourcesDir)
        assertEquals(true, options.useSystemLoadLibrary)
        assertEquals(listOf("-I/include"), options.clangArgs)
        assertEquals(1, options.libraries.size)
        assertEquals("Symbols", options.sharedClassName)
    }

    @Test
    fun `Library parse with name`() {
        val lib = Options.Library.parse("c")
        assertEquals("c", lib.libSpec)
        assertEquals(Options.Library.SpecKind.NAME, lib.specKind)
    }

    @Test
    fun `Library parse with path`() {
        val lib = Options.Library.parse(":lib/c.so")
        assertEquals("lib/c.so", lib.libSpec)
        assertEquals(Options.Library.SpecKind.PATH, lib.specKind)
    }

    @Test
    fun `Library parse with empty path throws`() {
        assertThrows<IllegalArgumentException> { Options.Library.parse(":") }
    }

    @Test
    fun `toQuotedName escapes backslashes`() {
        val lib = Options.Library("path\\to\\lib", Options.Library.SpecKind.PATH)
        assertEquals("path\\\\to\\\\lib", Options.Library.toQuotedName(lib))
    }

    @Test
    fun `default variadicArgs is empty`() {
        val opts = Options()
        assertTrue(opts.variadicArgs.isEmpty())
    }

    @Test
    fun `variadicArgs stores provided map`() {
        val map = mapOf("XCreateIC" to 11, "XSetICValues" to 3)
        val opts = Options(variadicArgs = map)
        assertEquals(11, opts.variadicArgs["XCreateIC"])
        assertEquals(3, opts.variadicArgs["XSetICValues"])
    }

    @Test
    fun `parseVariadicArgs valid input`() {
        val input = listOf("XCreateIC:11", "XSetICValues:3")
        val result = input.associate { arg ->
            val colon = arg.lastIndexOf(':')
            arg.substring(0, colon).trim() to arg.substring(colon + 1).trim().toInt()
        }
        assertEquals(11, result["XCreateIC"])
        assertEquals(3, result["XSetICValues"])
    }

    @Test
    fun `parseVariadicArgs missing colon throws`() {
        val input = listOf("BadFormat")
        assertThrows<IllegalArgumentException> {
            input.associate { arg ->
                val colon = arg.lastIndexOf(':')
                if (colon < 1) throw IllegalArgumentException("Invalid format")
                arg.substring(0, colon) to arg.substring(colon + 1).toInt()
            }
        }
    }
}
