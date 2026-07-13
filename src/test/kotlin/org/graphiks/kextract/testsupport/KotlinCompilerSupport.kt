package org.graphiks.kextract.testsupport

import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import java.nio.file.Files
import java.nio.file.Path

data class CompilationResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val failureArtifact: Path? = null,
)

object KotlinCompilerSupport {
    private val isWindows: Boolean
        get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    fun compilerPath(): Path {
        val kotlinc = System.getenv("KOTLINC")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?.takeIf(::isCompiler)
        val pathCompiler = System.getenv("PATH")
            ?.split(System.getProperty("path.separator"))
            ?.asSequence()
            ?.flatMap { directory ->
                sequenceOf(Path.of(directory, "kotlinc"), Path.of(directory, "kotlinc.bat"))
            }
            ?.firstOrNull(::isCompiler)
        return kotlinc ?: pathCompiler ?: error(
            "kotlinc not found: checked KOTLINC=${System.getenv("KOTLINC") ?: "<unset>"} " +
                "and executable kotlinc/kotlinc.bat entries on PATH=${System.getenv("PATH") ?: "<unset>"}"
        )
    }

    fun compile(files: List<KotlinSourceFile>): CompilationResult {
        val compiler = compilerPath()
        val artifactRoot = Path.of(
            System.getProperty("user.dir"),
            "build",
            "test-generated",
            "kotlin-compile-failures",
        )
        Files.createDirectories(artifactRoot)
        val directory = Files.createTempDirectory(artifactRoot, "compile-")
        return try {
            val sourcePaths = files.map { source ->
                directory.resolve(source.getPath()).also { path ->
                    path.parent?.let(Files::createDirectories)
                    Files.writeString(path, source.contents)
                }
            }
            val output = directory.resolve("generated.jar")
            val result = ProcessTestSupport.runSeparate(
                listOf(
                    *compilerCommand(compiler).toTypedArray(),
                    "-jdk-home", System.getProperty("java.home"),
                    "-d", output.toString(),
                ) + sourcePaths.map(Path::toString),
                directory,
            )
            if (result.exitCode == 0) {
                directory.toFile().deleteRecursively()
                CompilationResult(result.exitCode, result.stdout, result.stderr)
            } else {
                preserveFailure(directory, result.exitCode, result.stdout, result.stderr)
            }
        } catch (failure: Throwable) {
            preserveFailure(
                directory,
                exitCode = 1,
                stdout = "",
                stderr = "kotlinc invocation failed: ${failure.message ?: failure::class.simpleName}",
            )
        }
    }

    private fun isCompiler(path: Path): Boolean =
        Files.isRegularFile(path) && (Files.isExecutable(path) ||
            (isWindows && path.fileName.toString().endsWith(".bat", ignoreCase = true)))

    private fun compilerCommand(compiler: Path): List<String> =
        if (compiler.fileName.toString().endsWith(".bat", ignoreCase = true)) {
            val commandShell = System.getenv("COMSPEC")?.takeIf { it.isNotBlank() } ?: "cmd.exe"
            listOf(commandShell, "/c", "\"${compiler.toAbsolutePath()}\"")
        } else {
            listOf(compiler.toString())
        }

    private fun preserveFailure(
        directory: Path,
        exitCode: Int,
        stdout: String,
        stderr: String,
    ): CompilationResult {
        val diagnostics = directory.resolve("kotlinc-diagnostics.txt")
        Files.writeString(
            diagnostics,
            buildString {
                appendLine("exitCode=$exitCode")
                appendLine("--- stdout ---")
                append(stdout)
                if (!stdout.endsWith('\n')) appendLine()
                appendLine("--- stderr ---")
                append(stderr)
                if (!stderr.endsWith('\n')) appendLine()
            },
        )
        val artifact = directory.toAbsolutePath().normalize()
        val location = "Generated Kotlin sources and compiler diagnostics preserved at $artifact"
        val diagnosticOutput = if (stderr.isBlank()) location else "$stderr\n$location"
        return CompilationResult(exitCode, stdout, diagnosticOutput, artifact)
    }
}
