package org.graphiks.kextract.pipeline

import com.github.ajalt.clikt.core.main
import com.fasterxml.jackson.core.JsonProcessingException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KextractCommandTest {
    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `uses the default empty target package`() {
        val output = tempDir.resolve("generated")

        runCommand("--output", output.toString(), header().toString())

        assertFalse(Files.readString(output.resolve("input_h.kt")).contains("package "))
    }

    @Test
    fun `writes a Kotlin file to the requested output directory`() {
        val output = tempDir.resolve("generated")

        runCommand("--output", output.toString(), header().toString())

        assertTrue(Files.exists(output.resolve("input_h.kt")))
    }

    @Test
    fun `applies package library and function include options`() {
        val output = tempDir.resolve("generated")
        val header = header("int add(int a, int b);\nint subtract(int a, int b);")

        runCommand(
            "--output", output.toString(),
            "--target-package", "example.bindings",
            "--library", "math",
            "--include-function", "add",
            header.toString(),
        )

        val source = Files.readString(output.resolve("example/bindings/input_h.kt"))
        assertContains(source, "package example.bindings")
        assertContains(source, "SymbolLookup.libraryLookup(\"math\"")
        assertContains(source, "fun add(")
        assertFalse(source.contains("fun subtract("))
    }

    @Test
    fun `writes split output using function file names`() {
        val output = tempDir.resolve("generated")

        runCommand("--output", output.toString(), "--split-output", header().toString())

        assertTrue(Files.exists(output.resolve("input_h.kt")))
        assertTrue(Files.exists(output.resolve("functions/inputFunctions.kt")))
    }

    @Test
    fun `adds init method when requested`() {
        val output = tempDir.resolve("generated")

        runCommand("--output", output.toString(), "--init-method", header().toString())

        assertContains(Files.readString(output.resolve("input_h.kt")), "fun init()")
    }

    @Test
    fun `configures variadic function arguments`() {
        val output = tempDir.resolve("generated")
        val header = header("void log_message(const char* message, ...);")

        runCommand(
            "--output", output.toString(),
            "--variadic-args", "log_message:2",
            header.toString(),
        )

        assertContains(Files.readString(output.resolve("input_h.kt")), "firstVariadicArg(1)")
    }

    @Test
    fun `rejects win32 mode without DLL map`() {
        val error = assertFailsWith<IllegalArgumentException> {
            runCommand("--win32", header().toString())
        }

        assertEquals("--win32 requires --dll-map <file>", error.message)
    }

    @Test
    fun `rejects DLL map without win32 mode`() {
        val error = assertFailsWith<IllegalArgumentException> {
            runCommand("--dll-map", dllMap("dllMap: {}").toString(), header().toString())
        }

        assertEquals("--dll-map requires --win32", error.message)
    }

    @Test
    fun `rejects malformed DLL map YAML`() {
        assertFailsWith<JsonProcessingException> {
            runCommand("--win32", "--dll-map", dllMap("dllMap: [").toString(), header().toString())
        }
    }

    @Test
    fun `rejects invalid variadic argument counts`() {
        listOf("log_message:nope", "log_message:0", "missing-count").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                runCommand("--variadic-args", value, header().toString())
            }
        }
    }

    private fun runCommand(vararg args: String) {
        KextractCommand(logger()).main(args)
    }

    private fun header(contents: String = "int add(int a, int b);"): Path =
        tempDir.resolve("input.h").also { Files.writeString(it, contents) }

    private fun dllMap(contents: String): Path =
        tempDir.resolve("dll-map.yaml").also { Files.writeString(it, contents) }

    private fun logger(): Logger {
        val sink = PrintWriter(StringWriter(), true)
        return Logger(sink, sink)
    }
}
