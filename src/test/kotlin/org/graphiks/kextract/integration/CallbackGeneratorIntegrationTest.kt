package org.graphiks.kextract.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.Logger
import org.graphiks.kextract.pipeline.Options
import java.nio.file.Files

class CallbackGeneratorIntegrationTest : FreeSpec({
    fun generateKmp(header: String): Map<String, String> {
        val input = Files.createTempFile("kextract-callback-generator", ".h")
        val output = Files.createTempDirectory("kextract-callback-generator-out")
        return try {
            input.toFile().writeText(header)
            KextractTool(Logger()).runGeneration(
                listOf(input.toString()),
                Options(
                    targetPackage = "sample.bindings",
                    outputDir = output.toString(),
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

    val genericCallbacks = """
        typedef void (*SampleCallback)(unsigned int value, void * userdata1, void * userdata2);
        typedef void (*NoUserdataCallback)(unsigned int value);

        typedef struct SampleCallbackFields {
            SampleCallback callback;
        } SampleCallbackFields;

        void sample_set_callback(SampleCallback callback);
        unsigned int sample_get_value(unsigned int value);
    """.trimIndent()

    "common KMP output emits typed callback registrations for generic typedefs" {
        val common = generateKmp(genericCallbacks).getValue("commonMain")

        common shouldContain """
            fun interface SampleCallback : Callback {
                fun invoke(value: UInt, userdata1: NativeAddress?)
                companion object
            }
        """.trimIndent()
        common shouldContain """
            expect fun SampleCallback.Companion.register(
                policy: CallbackPolicy,
                onError: CallbackExceptionHandler = CallbackExceptionHandler.Default,
                callback: SampleCallback,
            ): CallbackRegistration<SampleCallback>
        """.trimIndent()
        common shouldNotContain "policy: CallbackPolicy ="
        common shouldNotContain "fun invoke(value: UInt, userdata1: NativeAddress?, userdata2: NativeAddress?)"
        common shouldContain """
            @CallbackRuntimeApi
            internal val SampleCallbackType: CallbackType<SampleCallback> = CallbackType(
                canonicalId = "typedef:SampleCallback",
                hasRoutingUserdata = true,
            )
        """.trimIndent()
        common shouldContain """
            @CallbackRuntimeApi
            internal expect fun SampleCallback.Companion.prepare(
                policy: CallbackPolicy,
                onError: CallbackExceptionHandler = CallbackExceptionHandler.Default,
                callback: SampleCallback,
            ): PreparedCallbackRegistration<SampleCallback>
        """.trimIndent()

        common shouldContain "var callback: NativeAddress?"
        common shouldContain "expect fun sample_set_callback(callback: NativeAddress?): Unit"
        common shouldNotContain "expect class SampleCallback"
        common shouldNotContain "fun allocate(callback:"
        common shouldNotContain "override fun close()"
    }

    "queue callback keeps application userdata and reserves final routing userdata" {
        val common = generateKmp(
            """
                typedef enum WGPUQueueWorkDoneStatus {
                    WGPUQueueWorkDoneStatus_Success = 0
                } WGPUQueueWorkDoneStatus;
                typedef struct WGPUStringView {
                    char const * data;
                    unsigned long long length;
                } WGPUStringView;
                typedef void (*WGPUQueueWorkDoneCallback)(
                    WGPUQueueWorkDoneStatus status,
                    WGPUStringView message,
                    void * userdata1,
                    void * userdata2
                );
            """.trimIndent(),
        ).getValue("commonMain")

        common shouldContain """
            fun interface WGPUQueueWorkDoneCallback : Callback {
                fun invoke(
                    status: WGPUQueueWorkDoneStatus,
                    message: WGPUStringView,
                    userdata1: NativeAddress?,
                )

                companion object
            }
        """.trimIndent()
        common shouldNotContain "userdata2: NativeAddress?"
    }

    "callbacks without userdata expose explicit unsafe re-arming" {
        val common = generateKmp(genericCallbacks).getValue("commonMain")

        common shouldContain """
            fun interface NoUserdataCallback : Callback {
                fun invoke(value: UInt)
                companion object
            }
        """.trimIndent()
        common shouldContain "expect fun NoUserdataCallback.Companion.register("
        common shouldContain """
            @UnsafeCallbackRearmApi
            expect fun NoUserdataCallback.Companion.rearmAfterNativeQuiescence(
        """.trimIndent()
        common shouldNotContain "SampleCallback.Companion.rearmAfterNativeQuiescence"
    }

    "ordinary generic functions remain generated in every KMP target" {
        val generated = generateKmp(genericCallbacks)

        generated.forEach { (_, source) ->
            source shouldContain "sample_get_value"
            source shouldContain "sample_set_callback(callback: NativeAddress?)"
        }
        generated.filterKeys { it != "commonMain" }.values.forEach { source ->
            source shouldNotContain "actual class SampleCallback"
        }
    }

    "Native raw callback arguments are reinterpreted as C function pointers" {
        val native = generateKmp(genericCallbacks).getValue("nativeMain")

        native shouldContain """
            actual fun sample_set_callback(callback: NativeAddress?): Unit {
                webgpu.native.sample_set_callback(callback?.pointer?.takeIf { callback.rawValue != 0L }?.reinterpret())
                return
            }
        """.trimIndent()
    }

    "non-void callbacks fail generation" {
        val input = Files.createTempFile("kextract-invalid-callback", ".h")
        val output = Files.createTempDirectory("kextract-invalid-callback-out")
        try {
            input.toFile().writeText("typedef int (*InvalidCallback)(unsigned int value);")

            KextractTool(Logger()).runGeneration(
                listOf(input.toString()),
                Options(outputDir = output.toString(), multiplatform = true),
            ) shouldBe KextractTool.FAILURE
        } finally {
            input.toFile().delete()
            output.toFile().deleteRecursively()
        }
    }
})
