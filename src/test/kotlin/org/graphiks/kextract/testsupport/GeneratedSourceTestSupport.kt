package org.graphiks.kextract.testsupport

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.cli.DllMap
import org.graphiks.kextract.kotlin.KotlinGenerator
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.NameMangler
import org.graphiks.kextract.pipeline.Options
import java.nio.file.Files
import java.nio.file.Path

enum class HeaderLanguage { C, OBJECTIVE_C }

data class GenerationRequest(
    val source: String,
    val language: HeaderLanguage = HeaderLanguage.C,
    val packageName: String = "test",
    val libraries: List<Options.Library> = emptyList(),
    val splitOutput: Boolean = false,
    val variadicArgs: Map<String, Int> = emptyMap(),
    val win32Mode: Boolean = false,
    val dllMap: DllMap? = null,
    val useInitMethod: Boolean = false,
)

object GeneratedSourceTestSupport {
    fun parse(request: GenerationRequest): Declaration.Scoped = withHeader(request) { header ->
        val parserOptions = if (request.language == HeaderLanguage.OBJECTIVE_C) {
            arrayOf("-x", "objective-c")
        } else {
            emptyArray()
        }
        KextractTool.parse(listOf(header.toString()), *parserOptions)
    }

    fun generate(request: GenerationRequest): List<KotlinSourceFile> = withHeader(request) { header ->
        val parsed = parse(request)
        val mangled = NameMangler(header.fileName.toString()).scan(parsed)
        KotlinGenerator().generate(
            mangled,
            header.fileName.toString(),
            request.packageName,
            request.libraries,
            splitOutput = request.splitOutput,
            variadicArgs = request.variadicArgs,
            win32Mode = request.win32Mode,
            dllMap = request.dllMap,
            useInitMethod = request.useInitMethod,
        )
    }

    fun contentByPath(files: List<KotlinSourceFile>): Map<String, String> =
        files.associate { it.getPath().toString() to it.contents }

    private fun <T> withHeader(request: GenerationRequest, block: (Path) -> T): T {
        val directory = Files.createTempDirectory("kextract-test-support-")
        val header = directory.resolve("input.h")
        return try {
            Files.writeString(header, request.source)
            block(header)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
