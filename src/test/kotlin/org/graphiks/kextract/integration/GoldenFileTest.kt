package org.graphiks.kextract.integration

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.ObjectMapper
import org.graphiks.kextract.cli.DllMap
import org.graphiks.kextract.testsupport.GeneratedSourceTestSupport
import org.graphiks.kextract.testsupport.GenerationRequest
import org.graphiks.kextract.testsupport.HeaderLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI

data class GoldenCase(
    val name: String,
    val root: Path,
    val request: GenerationRequest,
)

fun normalizeGeneratedSource(source: String): String {
    val lineNormalized = source.replace("\r\n", "\n").replace('\r', '\n')
    return lineNormalized
        .split('\n', limit = Int.MAX_VALUE)
        .joinToString("\n") { it.trimEnd(' ', '\t') }
        .let { if (it.endsWith('\n')) it else "$it\n" }
}

fun compareOrUpdateGolden(case: GoldenCase, generated: Map<String, String>, update: Boolean) {
    if (update) Files.createDirectories(case.root)
    val generatedByPath = generated.entries.associate { (path, content) ->
        normalizePath(path) to normalizeGeneratedSource(content)
    }
    require(generatedByPath.size == generated.size) {
        "Duplicate generated paths for fixture ${case.name}: ${generated.keys}"
    }

    val expectedByPath: Map<String, String> = if (Files.isDirectory(case.root)) {
        Files.walk(case.root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .map { path -> normalizePath(case.root.relativize(path).toString()) to normalizeGeneratedSource(Files.readString(path)) }
                .toList()
                .toMap()
        }
    } else {
        emptyMap()
    }

    if (update) {
        generatedByPath.toSortedMap().forEach { (relativePath, content) ->
            val destination = case.root.resolve(relativePath).normalize()
            require(destination.startsWith(case.root.normalize())) {
                "Generated path escapes fixture ${case.name}: $relativePath"
            }
            Files.createDirectories(destination.parent)
            Files.writeString(destination, content)
        }
        return
    }

    val generatedPaths = generatedByPath.keys.sorted()
    val expectedPaths = expectedByPath.keys.sorted()
    if (generatedPaths != expectedPaths) {
        throw AssertionError(
            "Golden file set mismatch\n" +
                "fixture: ${case.name}\n" +
                "root: ${case.root}\n" +
                "missing generated files: ${expectedPaths - generatedPaths}\n" +
                "unexpected generated files: ${generatedPaths - expectedPaths}",
        )
    }

    generatedPaths.forEach { relativePath ->
        val expected = expectedByPath.getValue(relativePath)
        val actual = generatedByPath.getValue(relativePath)
        if (expected != actual) {
            throw AssertionError(
                "Golden content mismatch\n" +
                    "fixture: ${case.name}\n" +
                    "file: $relativePath\n" +
                    unifiedDiff(expected, actual),
            )
        }
    }
}

private fun normalizePath(path: String): String = path.replace('\\', '/')

private fun unifiedDiff(expected: String, actual: String): String {
    val expectedLines = expected.split('\n', limit = Int.MAX_VALUE)
    val actualLines = actual.split('\n', limit = Int.MAX_VALUE)
    val firstDifferent = (0 until maxOf(expectedLines.size, actualLines.size))
        .firstOrNull { index -> expectedLines.getOrNull(index) != actualLines.getOrNull(index) }
        ?: return ""
    val start = (firstDifferent - 2).coerceAtLeast(0)
    val end = (firstDifferent + 5).coerceAtMost(maxOf(expectedLines.size, actualLines.size))
    return buildString {
        appendLine("@@ line ${firstDifferent + 1} @@")
        for (index in start until end) {
            val before = expectedLines.getOrNull(index)
            val after = actualLines.getOrNull(index)
            if (before == after) {
                appendLine("  ${before.orEmpty()}")
            } else {
                before?.let { appendLine("- $it") }
                after?.let { appendLine("+ $it") }
            }
        }
    }
}

class GoldenFileTest {
    @Test
    fun `normalization preserves blank lines and only trims terminal spaces`() {
        assertEquals(
            "first\n\nsecond\nthird\n",
            normalizeGeneratedSource("first \r\n\r\nsecond\t\rthird"),
        )
    }

    @Test
    fun `C structs and functions matches its reference`() = assertGolden(
        goldenCase("c/structs-and-functions.h", "C structs and functions")
    )

    @Test
    fun `C constants and macros matches its reference`() = assertGolden(
        goldenCase("c/constants-and-macros.h", "C constants and macros")
    )

    @Test
    fun `C variadic declarations matches its reference`() = assertGolden(
        goldenCase("c/variadic.h", "C variadic declarations") {
            GenerationRequest(
                source = it,
                packageName = "",
                variadicArgs = mapOf("log_values" to 2, "format_message" to 3),
            )
        }
    )

    @Test
    @EnabledOnOs(OS.MAC)
    fun `Objective C classes and protocols matches its reference`() = assertGolden(
        goldenCase("objc/classes-and-protocols.h", "Objective-C classes and protocols") {
            GenerationRequest(source = it, language = HeaderLanguage.OBJECTIVE_C, packageName = "")
        }
    )

    @Test
    @EnabledOnOs(OS.MAC)
    fun `Objective C categories and properties matches its reference`() = assertGolden(
        goldenCase("objc/categories-and-properties.h", "Objective-C categories and properties") {
            GenerationRequest(source = it, language = HeaderLanguage.OBJECTIVE_C, packageName = "")
        }
    )

    @Test
    fun `Win32 declarations matches its reference without loading a DLL`() {
        val header = fixture("golden/win32/sample.h")
        val root = header.parent
        val dllMap = ObjectMapper(YAMLFactory()).readValue(
            root.resolve("sample.yml").toFile(),
            DllMap::class.java,
        )
        assertGolden(
            GoldenCase(
                name = "Win32 declarations",
                root = root.resolve("sample"),
                request = GenerationRequest(
                    source = Files.readString(header),
                    packageName = "",
                    splitOutput = true,
                    useInitMethod = true,
                    win32Mode = true,
                    dllMap = dllMap,
                ),
            )
        )
    }

    private fun assertGolden(case: GoldenCase) {
        val generated = GeneratedSourceTestSupport.contentByPath(
            GeneratedSourceTestSupport.generate(case.request)
        )
        compareOrUpdateGolden(case, generated, System.getProperty("golden.update") == "true")
    }

    private fun goldenCase(
        relativeHeader: String,
        name: String,
        configure: (String) -> GenerationRequest = {
            GenerationRequest(source = it, packageName = "")
        },
    ): GoldenCase {
        val header = fixture("golden/$relativeHeader")
        val fixtureRoot = header.parent.resolve(header.fileName.toString().substringBeforeLast('.'))
        return GoldenCase(name, fixtureRoot, configure(Files.readString(header)))
    }

    private fun fixture(resource: String): Path {
        val sourceResource = Path.of("src/test/resources").resolve(resource)
        if (Files.exists(sourceResource)) return sourceResource

        val url = Thread.currentThread().contextClassLoader.getResource(resource)
            ?: error("Fixture not found on classpath: $resource")
        return Path.of(URI(url.toString()))
    }
}
