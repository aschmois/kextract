package org.graphiks.kextract.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class KmpJvmPackedLayoutTest : FreeSpec({
    "generated JVM layouts preserve packed Clang offsets and alignments" {
        val generated = generateKmpSources(
            """
            typedef struct __attribute__((packed)) PackedLeaf {
                char tag;
                int value;
            } PackedLeaf;

            typedef struct __attribute__((packed)) PackedRecord {
                char prefix;
                int values[2];
                PackedLeaf leaf;
                short tail;
            } PackedRecord;
            """.trimIndent(),
        )

        generated.jvm shouldContain
            "MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT.withByteAlignment(1))" +
            ".withByteAlignment(1).withName(\"values\")"
        generated.jvm shouldNotContain
            "MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT)" +
            ".withByteAlignment(1).withName(\"values\")"

        val result = compileAndInvokeGeneratedKmpJvm(
            generated = generated,
            probePackage = "sample.probe",
            probeSource =
                """
                package sample.probe

                import java.lang.foreign.MemoryLayout.PathElement.groupElement
                import sample.bindings.PackedLeaf
                import sample.bindings.PackedRecord

                fun inspectPackedLayouts(): LongArray = longArrayOf(
                    PackedLeaf.layout.byteSize(),
                    PackedLeaf.layout.byteAlignment(),
                    PackedLeaf.layout.byteOffset(groupElement("tag")),
                    PackedLeaf.layout.byteOffset(groupElement("value")),
                    PackedRecord.layout.byteSize(),
                    PackedRecord.layout.byteAlignment(),
                    PackedRecord.layout.byteOffset(groupElement("prefix")),
                    PackedRecord.layout.byteOffset(groupElement("values")),
                    PackedRecord.layout.byteOffset(groupElement("leaf")),
                    PackedRecord.layout.byteOffset(groupElement("tail")),
                )
                """.trimIndent(),
            facadeClassName = "ProbeKt",
            methodName = "inspectPackedLayouts",
        ) as LongArray

        result.toList() shouldBe listOf(5L, 1L, 0L, 1L, 16L, 1L, 0L, 1L, 9L, 14L)
    }
})
