package org.graphiks.kextract.testsupport

import java.nio.file.Path

data class ProcessResult(
    val exitCode: Int,
    val output: String,
)

object ProcessTestSupport {
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
}
