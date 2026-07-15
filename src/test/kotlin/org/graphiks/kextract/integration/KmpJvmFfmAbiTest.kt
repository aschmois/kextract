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

    "generated JVM union preserves Clang tail padding and remains sequence-compatible" {
        val generated = generateKmpSources(
            """
            typedef union U {
                char bytes[3];
                short s;
            } U;
            """.trimIndent(),
        )

        val result = compileAndInvokeGeneratedKmpJvm(
            generated = generated,
            probePackage = "sample.probe",
            probeSource =
                """
                package sample.probe

                import java.lang.foreign.MemoryLayout
                import java.lang.foreign.MemoryLayout.PathElement.groupElement
                import sample.bindings.U

                fun inspectUnionTailPadding(): LongArray {
                    val sequence = MemoryLayout.sequenceLayout(2, U.layout)
                    return longArrayOf(
                        U.layout.byteSize(),
                        U.layout.byteAlignment(),
                        U.layout.byteOffset(groupElement("bytes")),
                        U.layout.byteOffset(groupElement("s")),
                        sequence.byteSize(),
                        sequence.byteAlignment(),
                    )
                }
                """.trimIndent(),
            facadeClassName = "ProbeKt",
            methodName = "inspectUnionTailPadding",
        ) as LongArray

        result.toList() shouldBe listOf(4L, 2L, 0L, 0L, 8L, 2L)
        generated.jvm shouldContain "MemoryLayout.unionLayout("
        generated.jvm shouldContain "java.lang.foreign.MemoryLayout.paddingLayout(4)"
    }

    "unnamed nested record layouts preserve named group paths and Clang offsets" {
        val anonymousPair = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withByteAlignment(4).withName("x"),
            ValueLayout.JAVA_SHORT.withByteAlignment(2).withName("y"),
            MemoryLayout.paddingLayout(2),
        ).withByteAlignment(4)
        val anonymousData = MemoryLayout.unionLayout(
            anonymousPair.withName("pair"),
            ValueLayout.JAVA_LONG.withByteAlignment(8).withName("wide"),
        ).withByteAlignment(8)
        val outer = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withByteAlignment(4).withName("tag"),
            MemoryLayout.paddingLayout(4),
            anonymousData.withName("data"),
            ValueLayout.JAVA_INT.withByteAlignment(4).withName("tail"),
            MemoryLayout.paddingLayout(4),
        ).withByteAlignment(8).withName("Outer")

        listOf(
            outer.byteSize(),
            outer.byteAlignment(),
            outer.byteOffset(groupElement("tag")),
            outer.byteOffset(groupElement("data")),
            outer.byteOffset(groupElement("data"), groupElement("pair")),
            outer.byteOffset(
                groupElement("data"),
                groupElement("pair"),
                groupElement("x"),
            ),
            outer.byteOffset(
                groupElement("data"),
                groupElement("pair"),
                groupElement("y"),
            ),
            outer.byteOffset(groupElement("data"), groupElement("wide")),
            outer.byteOffset(groupElement("tail")),
        ) shouldBe listOf(24L, 8L, 0L, 8L, 8L, 8L, 12L, 8L, 16L)

        anonymousPair.name().isEmpty shouldBe true
        anonymousData.name().isEmpty shouldBe true
        outer.name().orElseThrow() shouldBe "Outer"
        outer.select(groupElement("data")).name().orElseThrow() shouldBe "data"
        outer.select(groupElement("data"), groupElement("pair")).name().orElseThrow() shouldBe "pair"
    }
})
