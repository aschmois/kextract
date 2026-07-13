package org.graphiks.kextract.integration

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.ObjectMapper
import org.graphiks.kextract.cli.DllEntry
import org.graphiks.kextract.cli.DllMap
import org.graphiks.kextract.pipeline.Options
import org.graphiks.kextract.testsupport.GeneratedSourceTestSupport
import org.graphiks.kextract.testsupport.GenerationRequest
import org.graphiks.kextract.testsupport.KotlinCompilerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class Win32GenerationTest {
    @Test
    fun `Win32 lookup routes functions and constants and falls back for unknown symbols`() {
        val generated = mainSource(sampleRequest(sampleDllMap()))

        assertEquals(
            1,
            generated.countOccurrences("SymbolLookup.libraryLookup(\"kernel32.dll\", Arena.global())"),
        )
        assertEquals(
            1,
            generated.countOccurrences("SymbolLookup.libraryLookup(\"user32.dll\", Arena.global())"),
        )
        assertTrue(generated.contains("\"GetWindowText\" -> _DLL_KERNEL32_DLL"))
        assertTrue(generated.contains("\"CreateWindow\", \"WindowStyle\" -> _DLL_USER32_DLL"))
        assertTrue(generated.contains("else -> SymbolLookup.loaderLookup()"))

        val lookupBranch = generated
            .substringAfter("private fun _lookup")
            .substringBefore("\n\n")
        assertFalse(lookupBranch.contains("\"Window\""))
    }

    @Test
    fun `Win32 DLL names are Kotlin safe and all generated files compile`() {
        val dllMap = DllMap(
            mapOf(
                "kernel32.dll" to DllEntry(functions = listOf("GetWindowText")),
                "user-32.dll" to DllEntry(
                    functions = listOf("CreateWindow"),
                    constants = listOf("WindowStyle"),
                    structs = listOf("Window"),
                ),
            ),
        )
        val request = sampleRequest(dllMap)
        val generated = GeneratedSourceTestSupport.generate(request)
        val main = GeneratedSourceTestSupport.contentByPath(generated).getValue("input_h.kt")

        assertTrue(main.contains("private val _DLL_KERNEL32_DLL: SymbolLookup?"))
        assertTrue(main.contains("private val _DLL_USER_32_DLL: SymbolLookup?"))
        assertTrue(main.contains("\"user-32.dll\""))

        val result = KotlinCompilerSupport.compile(generated)
        assertEquals(0, result.exitCode, "${result.stdout}\n${result.stderr}")
    }

    @Test
    fun `useInitMethod defers DLL and symbol lookup until init`() {
        val generated = mainSource(
            sampleRequest(
                sampleDllMap(),
                useInitMethod = true,
            ),
        )
        val initPosition = generated.indexOf("fun init()")

        assertTrue(generated.contains("private var _DLL_KERNEL32_DLL: SymbolLookup? = null"))
        assertTrue(generated.contains("private var _DLL_USER32_DLL: SymbolLookup? = null"))
        assertTrue(generated.contains("private var _initialized: Boolean = false"))
        assertTrue(initPosition >= 0)
        assertTrue(
            generated.indexOf("SymbolLookup.libraryLookup(\"kernel32.dll\"", initPosition) > initPosition,
        )
        assertTrue(generated.contains("_DLL_KERNEL32_DLL = try {"))
        assertTrue(generated.contains("WindowStyle_SEGMENT = _lookup(\"WindowStyle\")"))
        assertTrue(generated.contains("CreateWindow_HANDLE = _lookup(\"CreateWindow\")"))
        assertFalse(generated.contains("private val _DLL_KERNEL32_DLL: SymbolLookup? = try"))
        assertFalse(generated.contains("private val _DLL_USER32_DLL: SymbolLookup? = try"))
    }

    @Test
    fun `ordinary library mode does not emit a per DLL lookup table`() {
        val generated = mainSource(
            GenerationRequest(
                source = "int add(int left, int right);",
                libraries = listOf(Options.Library.parse("ordinary")),
            ),
        )

        assertTrue(generated.contains("private val LOOKUP: SymbolLookup = run {"))
        assertTrue(generated.contains("SymbolLookup.libraryLookup(\"ordinary\", Arena.global())"))
        assertFalse(generated.contains("private fun _lookup"))
        assertFalse(generated.contains("_DLL_"))
    }

    private fun sampleRequest(
        dllMap: DllMap,
        useInitMethod: Boolean = false,
    ): GenerationRequest = GenerationRequest(
        source = Files.readString(fixture("golden/win32/sample.h")),
        packageName = "",
        win32Mode = true,
        dllMap = dllMap,
        useInitMethod = useInitMethod,
    )

    private fun sampleDllMap(): DllMap {
        val root = fixture("golden/win32")
        return ObjectMapper(YAMLFactory()).readValue(
            root.resolve("sample.yml").toFile(),
            DllMap::class.java,
        )
    }

    private fun mainSource(request: GenerationRequest): String =
        GeneratedSourceTestSupport.contentByPath(
            GeneratedSourceTestSupport.generate(request),
        ).values.single()

    private fun fixture(resource: String): Path {
        val sourceResource = Path.of("src/test/resources").resolve(resource)
        if (Files.exists(sourceResource)) return sourceResource

        val url = Thread.currentThread().contextClassLoader.getResource(resource)
            ?: error("Fixture not found on classpath: $resource")
        return Path.of(URI(url.toString()))
    }
}

private fun String.countOccurrences(needle: String): Int =
    windowed(needle.length).count { it == needle }
