package org.graphiks.kextract.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class UnixLauncherTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `Linux launcher prepends runtime libjsig and preserves inherited preload`() {
        val distribution = temporaryDirectory.resolve("distribution with spaces")
        val launcher = distribution.resolve("bin/kextract")
        launcher.parent.createDirectories()
        Files.copy(
            Path.of("src/main/kextract"),
            launcher,
            StandardCopyOption.REPLACE_EXISTING,
        )

        val localLibjsig = localLibjsig()
        val runtimeLibjsig = distribution.resolve("runtime/lib/libjsig.so")
        runtimeLibjsig.parent.createDirectories()
        Files.copy(localLibjsig, runtimeLibjsig, StandardCopyOption.REPLACE_EXISTING)

        val inheritedLibjsig = temporaryDirectory.resolve("inherited/libjsig.so")
        inheritedLibjsig.parent.createDirectories()
        Files.copy(localLibjsig, inheritedLibjsig, StandardCopyOption.REPLACE_EXISTING)
        val inheritedLibraryPath = temporaryDirectory.resolve("inherited/lib").createDirectories()

        val fakeCommands = temporaryDirectory.resolve("commands").createDirectories()
        fakeCommands.resolve("uname").writeExecutable(
            """
            #!/bin/sh
            printf 'Linux\n'
            """.trimIndent(),
        )

        val fakeJava = distribution.resolve("runtime/bin/java")
        fakeJava.parent.createDirectories()
        fakeJava.writeExecutable(
            """
            #!/bin/sh
            printf 'LD_PRELOAD=%s\n' "${'$'}LD_PRELOAD"
            printf 'LD_LIBRARY_PATH=%s\n' "${'$'}LD_LIBRARY_PATH"

            original_ifs=${'$'}IFS
            IFS=' :'
            set -- ${'$'}LD_PRELOAD
            IFS=${'$'}original_ifs
            first_preload=${'$'}1

            case ${'$'}first_preload in
                */*)
                    [ -f "${'$'}first_preload" ] || exit 71
                    ;;
                *)
                    resolved=false
                    IFS=:
                    for directory in ${'$'}LD_LIBRARY_PATH; do
                        if [ -f "${'$'}directory/${'$'}first_preload" ]; then
                            resolved=true
                            break
                        fi
                    done
                    IFS=${'$'}original_ifs
                    [ "${'$'}resolved" = true ] || exit 72
                    ;;
            esac
            """.trimIndent(),
        )

        val process = ProcessBuilder("/bin/sh", launcher.toString())
            .redirectErrorStream(true)
        process.environment()["PATH"] =
            "${fakeCommands}${File.pathSeparator}${System.getenv("PATH").orEmpty()}"
        process.environment()["LD_PRELOAD"] = inheritedLibjsig.toString()
        process.environment()["LD_LIBRARY_PATH"] = inheritedLibraryPath.toString()

        val runningProcess = process.start()
        val output = runningProcess.inputStream.bufferedReader().readText()
        val exitCode = runningProcess.waitFor()

        assertEquals(0, exitCode, output)
        assertEquals(
            listOf(
                "LD_PRELOAD=libjsig.so:${inheritedLibjsig}",
                "LD_LIBRARY_PATH=${runtimeLibjsig.toRealPath().parent}:${inheritedLibraryPath}",
            ),
            output.lineSequence().filter(String::isNotBlank).toList(),
        )
    }

    private fun localLibjsig(): Path {
        val javaLibDirectory = Path.of(System.getProperty("java.home"), "lib")
        return listOf(
            javaLibDirectory.resolve("libjsig.so"),
            javaLibDirectory.resolve("libjsig.dylib"),
        ).firstOrNull(Files::isRegularFile)
            ?: error("No local libjsig found under $javaLibDirectory")
    }

    private fun Path.writeExecutable(content: String) {
        writeText("$content\n")
        check(toFile().setExecutable(true)) { "Could not make $this executable" }
    }
}
