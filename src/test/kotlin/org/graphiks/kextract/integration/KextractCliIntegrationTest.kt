package org.graphiks.kextract.integration

import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.testsupport.ProcessTestSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KextractCliIntegrationTest {
    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `generates Kotlin through the CLI child process`() {
        val header = header("int add(int a, int b);")
        val output = tempDir.resolve("generated")

        val result = runCli("--output", output.toString(), header.toString())

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(Files.exists(output.resolve("input_h.kt")))
        assertFalseFatal(result.stderr)
    }

    @Test
    fun `reports a missing header through the CLI child process`() {
        val missing = tempDir.resolve("missing.h")

        val result = runCli(missing.toString())

        assertEquals(KextractTool.CLANG_ERROR, result.exitCode, result.stderr)
        assertContains(result.stderr, "missing.h")
        assertFalse(result.stderr.contains("kextract.fatal"), result.stderr)
    }

    @Test
    fun `returns clang error when parsing fails after a clang diagnostic`() {
        val header = header("int add(int a, int b);")

        val result = runCli("--clang-arg=-invalid-clang-option", header.toString())

        assertEquals(KextractTool.CLANG_ERROR, result.exitCode, result.stderr)
        assertContains(result.stderr, "unknown argument")
        assertFalse(result.stderr.contains("kextract.fatal"), result.stderr)
    }

    @Test
    fun `keeps a libclang warning non-fatal`() {
        val header = header("#warning task-2-warning\nint add(int a, int b);\n")

        val result = runCli(header.toString())

        assertEquals(KextractTool.SUCCESS, result.exitCode, result.stderr)
        assertContains(result.stderr, "task-2-warning")
        assertFalse(result.stderr.contains("kextract.fatal"), result.stderr)
    }

    @Test
    fun `reports malformed variadic arguments through the CLI child process`() {
        val header = header("void log_message(const char* message, ...);")

        val result = runCli("--variadic-args", "log_message:not-a-count", header.toString())

        assertNotEquals(0, result.exitCode)
        assertContains(result.stderr, "Invalid count in --variadic-args 'log_message:not-a-count'")
    }

    private fun runCli(vararg args: String) = ProcessTestSupport.runSeparate(
        command = buildList {
            add(javaExecutable().toString())
            add("--enable-native-access=ALL-UNNAMED")
            add("-Djava.library.path=${System.getProperty("java.library.path")}")
            add("-cp")
            add(System.getProperty("java.class.path"))
            add("org.graphiks.kextract.pipeline.KextractTool")
            addAll(args)
        },
        workingDirectory = tempDir,
    )

    private fun header(contents: String): Path =
        tempDir.resolve("input.h").also { Files.writeString(it, contents) }

    private fun javaExecutable(): Path = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    )

    private fun assertFalseFatal(stderr: String) {
        assertTrue(!stderr.contains("kextract.fatal"), stderr)
    }
}
