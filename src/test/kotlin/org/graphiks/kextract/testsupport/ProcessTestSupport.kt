package org.graphiks.kextract.testsupport

import java.nio.file.Path

data class ProcessResult(
    val exitCode: Int,
    val output: String,
)

data class SeparateProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object ProcessTestSupport {
    fun runKextractTool(
        workingDirectory: Path,
        vararg args: String,
    ): SeparateProcessResult = runSeparate(
        command = buildList {
            add(javaExecutable().toString())
            add("--enable-native-access=ALL-UNNAMED")
            add("-Djava.library.path=${System.getProperty("java.library.path")}")
            add("-cp")
            add(System.getProperty("java.class.path"))
            add("org.graphiks.kextract.pipeline.KextractTool")
            addAll(args)
        },
        workingDirectory = workingDirectory,
    )

    fun run(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), output)
    }

    fun runSeparate(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String> = emptyMap(),
    ): SeparateProcessResult {
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .apply { environment().putAll(environment) }
            .start()
        var stdout = ""
        var stderr = ""
        val stdoutReader = Thread { stdout = process.inputStream.bufferedReader().readText() }
        val stderrReader = Thread { stderr = process.errorStream.bufferedReader().readText() }
        stdoutReader.start()
        stderrReader.start()
        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()
        return SeparateProcessResult(exitCode, stdout, stderr)
    }

    private fun javaExecutable(): Path = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    )
}
