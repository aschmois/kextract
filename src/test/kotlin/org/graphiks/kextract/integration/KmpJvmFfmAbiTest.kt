package org.graphiks.kextract.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.Logger
import org.graphiks.kextract.pipeline.Options
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.ValueLayout
import java.nio.file.Files

class KmpJvmFfmAbiTest : FreeSpec({
    fun generateJvm(header: String): String {
        val input = Files.createTempFile("kextract-kmp-jvm-abi", ".h")
        val output = Files.createTempDirectory("kextract-kmp-jvm-abi-out")
        return try {
            input.toFile().writeText(header)
            KextractTool(Logger.DEFAULT).runGeneration(
                listOf(input.toString()),
                Options(
                    targetPackage = "sample.bindings",
                    outputDir = output.toString(),
                    multiplatform = true,
                ),
            ) shouldBe KextractTool.SUCCESS

            Files.walk(output.resolve("jvmMain")).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".kt") }
                    .map { it.toFile().readText() }
                    .toList()
                    .joinToString("\n")
            }
        } finally {
            input.toFile().delete()
            output.toFile().deleteRecursively()
        }
    }

    "generated JVM union layout overlaps member storage" {
        val source = generateJvm(
            """
            typedef union WGPUScalar {
                unsigned int u32;
                float f32;
                unsigned long long u64;
            } WGPUScalar;
            """.trimIndent(),
        )

        source shouldContain "actual interface WGPUScalar : CStructure"
        val declaration = source.substringAfter("actual interface WGPUScalar")
        declaration shouldContain "MemoryLayout.unionLayout("
        declaration shouldNotContain "MemoryLayout.structLayout("

        val layout = MemoryLayout.unionLayout(
            ValueLayout.JAVA_INT.withName("u32"),
            ValueLayout.JAVA_FLOAT.withName("f32"),
            ValueLayout.JAVA_LONG.withName("u64"),
        )
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(layout)
            val u32 = layout.varHandle(groupElement("u32"))
            val f32 = layout.varHandle(groupElement("f32"))
            u32.set(segment, 0L, 0x3f800000)
            (f32.get(segment, 0L) as Float) shouldBe 1.0f
            f32.set(segment, 0L, 2.0f)
            (u32.get(segment, 0L) as Int) shouldBe 0x40000000
        }
    }
})
