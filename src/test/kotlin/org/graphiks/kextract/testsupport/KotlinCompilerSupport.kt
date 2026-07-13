package org.graphiks.kextract.testsupport

import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import java.nio.file.Files
import java.nio.file.Path

data class CompilationResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object KotlinCompilerSupport {
    fun compilerPath(): Path {
        val kotlinc = System.getenv("KOTLINC")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
        val pathCompiler = System.getenv("PATH")
            ?.split(System.getProperty("path.separator"))
            ?.asSequence()
            ?.map { Path.of(it, "kotlinc") }
            ?.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        return kotlinc ?: pathCompiler ?: error(
            "kotlinc not found: checked KOTLINC=${System.getenv("KOTLINC") ?: "<unset>"} " +
                "and executable kotlinc entries on PATH=${System.getenv("PATH") ?: "<unset>"}"
        )
    }

    fun compile(files: List<KotlinSourceFile>): CompilationResult {
        val directory = Files.createTempDirectory("kextract-kotlin-compile-")
        return try {
            val sourcePaths = files.map { source ->
                directory.resolve(source.getPath()).also { path ->
                    Files.createDirectories(path.parent)
                    Files.writeString(path, source.contents)
                }
            }
            val output = directory.resolve("generated.jar")
            val result = ProcessTestSupport.runSeparate(
                listOf(
                    compilerPath().toString(),
                    "-jdk-home", System.getProperty("java.home"),
                    "-d", output.toString(),
                ) + sourcePaths.map(Path::toString),
                directory,
            )
            CompilationResult(result.exitCode, result.stdout, result.stderr)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
