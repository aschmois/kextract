package org.graphiks.kextract.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DllMapTest {
    @TempDir
    private lateinit var tempDir: Path

    private val mapper = ObjectMapper(YAMLFactory())

    @Test
    fun `reads DLL symbols from YAML`() {
        val map = readYaml(
            """
            dllMap:
              user32.dll:
                functions: [CreateWindow]
                structs: [Window]
                constants: [WindowStyle]
            """.trimIndent()
        )

        val entry = map.dllMap.getValue("user32.dll")
        assertEquals(listOf("CreateWindow"), entry.functions)
        assertEquals(listOf("Window"), entry.structs)
        assertEquals(listOf("WindowStyle"), entry.constants)
    }

    @Test
    fun `reads an empty DLL mapping`() {
        assertEquals(emptyMap(), readYaml("dllMap: {}").dllMap)
    }

    @Test
    fun `rejects malformed YAML`() {
        assertFails { readYaml("dllMap: [") }
    }

    private fun readYaml(contents: String): DllMap {
        val yaml = tempDir.resolve("dll-map.yaml")
        Files.writeString(yaml, contents)
        return mapper.readValue(yaml.toFile(), DllMap::class.java)
    }
}
