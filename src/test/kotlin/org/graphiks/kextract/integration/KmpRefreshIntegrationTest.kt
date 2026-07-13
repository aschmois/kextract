package org.graphiks.kextract.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.graphiks.kextract.kotlin.KotlinGenerator
import org.graphiks.kextract.pipeline.IncludeHelper
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.Logger
import org.graphiks.kextract.pipeline.Options
import java.nio.file.Files

class KmpRefreshIntegrationTest : FreeSpec({
    fun generateKmp(header: String, includedFunctions: List<String> = emptyList()): Map<String, String> {
        val input = Files.createTempFile("kextract-kmp-refresh", ".h")
        val output = Files.createTempDirectory("kextract-kmp-refresh-out")
        return try {
            input.toFile().writeText(header)
            KextractTool(Logger.DEFAULT).runGeneration(
                listOf(input.toString()),
                Options(
                    targetPackage = "sample.bindings",
                    outputDir = output.toString(),
                    includeHelper = IncludeHelper().also { helper ->
                        includedFunctions.forEach { function ->
                            helper.addSymbol(IncludeHelper.IncludeKind.FUNCTION, function)
                        }
                    },
                    multiplatform = true,
                ),
            ) shouldBe KextractTool.SUCCESS

            listOf("commonMain", "jvmMain", "nativeMain", "androidMain").associateWith { sourceSet ->
                Files.walk(output.resolve(sourceSet)).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".kt") }
                        .map { it.toFile().readText() }
                        .toList()
                        .joinToString("\n")
                }
            }
        } finally {
            input.toFile().delete()
            output.toFile().deleteRecursively()
        }
    }

    "multiplatform mode emits common, JVM, Native, and Android bindings" {
        val generated = generateKmp(
            """
            typedef struct WGPUDeviceImpl* WGPUDevice;
            WGPUDevice wgpuGetDevice(void);
            """.trimIndent(),
        )

        generated.keys shouldBe setOf("commonMain", "jvmMain", "nativeMain", "androidMain")
        generated.getValue("commonMain") shouldContain "expect value class WGPUDevice"
        generated.getValue("jvmMain") shouldContain "actual value class WGPUDevice"
        generated.getValue("nativeMain") shouldContain "actual value class WGPUDevice"
        generated.getValue("androidMain") shouldContain "actual value class WGPUDevice"
    }

    "multiplatform mode honors include filters" {
        val generated = generateKmp(
            """
            int wgpuIncluded(void);
            int wgpuExcluded(void);
            """.trimIndent(),
            includedFunctions = listOf("wgpuIncluded"),
        )

        generated.values.forEach { source ->
            source shouldContain "wgpuIncluded"
            source shouldNotContain "wgpuExcluded"
        }
    }

    "empty target package keeps generated paths relative" {
        val input = Files.createTempFile("kextract-kmp-empty-package", ".h")
        try {
            input.toFile().writeText("int wgpuFunction(void);")
            val parsed = KextractTool.parse(listOf(input.toString()))
            val files = KotlinGenerator().generate(
                parsed,
                input.fileName.toString(),
                targetPackage = "",
                multiplatform = true,
            )

            files.all { !it.getPath().isAbsolute } shouldBe true
        } finally {
            Files.deleteIfExists(input)
        }
    }

    "empty target package routes Android JNA support to androidMain" {
        val input = Files.createTempFile("kextract-kmp-empty-package-routing", ".h")
        val output = Files.createTempDirectory("kextract-kmp-empty-package-routing-out")
        try {
            input.toFile().writeText("int wgpuFunction(void);")
            KextractTool(Logger.DEFAULT).runGeneration(
                listOf(input.toString()),
                Options(outputDir = output.toString(), multiplatform = true),
            ) shouldBe KextractTool.SUCCESS

            val androidSupport = output.resolve("androidMain/kotlin/android")
            Files.isDirectory(androidSupport) shouldBe true
            Files.walk(androidSupport).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".kt") }
                    .anyMatch { it.toFile().readText().contains("package android") } shouldBe true
            }
            Files.exists(output.resolve("commonMain/kotlin/android")) shouldBe false
        } finally {
            input.toFile().delete()
            output.toFile().deleteRecursively()
        }
    }

    "KMP common output preserves C documentation" {
        val generated = generateKmp(
            """
            /** Completes all work submitted before this call. */
            void wgpuQueueDone(void);
            """.trimIndent(),
        )

        generated.getValue("commonMain") shouldContain
            "Completes all work submitted before this call."
    }
})
