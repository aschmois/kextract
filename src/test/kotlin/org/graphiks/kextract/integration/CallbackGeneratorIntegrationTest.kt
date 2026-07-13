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

    val abiCallbacks = """
        typedef enum LargeStatus {
            LargeStatus_Zero = 0,
            LargeStatus_High = 0x100000000ULL
        } LargeStatus;

        typedef enum LargeOptions {
            LargeOptions_None = 0,
            LargeOptions_High = 0x100000000ULL
        } LargeOptions;

        typedef enum NarrowOptions {
            NarrowOptions_None = 0,
            NarrowOptions_High = 0x80000000U
        } NarrowOptions;

        typedef struct SamplePayload {
            unsigned long long value;
        } SamplePayload;

        typedef struct WGPUDeviceImpl * WGPUDevice;

        typedef void (*AbiCallback)(
            LargeStatus status,
            LargeOptions options,
            SamplePayload payload,
            WGPUDevice const * device,
            void * userdata
        );

        typedef void (*NarrowOptionsCallback)(
            NarrowOptions options,
            void * userdata
        );
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

    "JVM callbacks use one permanent static trampoline per callback type" {
        val jvm = generateKmp(genericCallbacks).getValue("jvmMain")

        listOf("SampleCallback", "NoUserdataCallback").forEach { callbackType ->
            jvm shouldContain "@OptIn(CallbackRuntimeApi::class)\nprivate object ${callbackType}Trampoline"
            val trampoline = jvm
                .substringAfter("private object ${callbackType}Trampoline")
                .substringBefore("\n}\n")
            trampoline shouldContain "MethodHandles.lookup().findStatic("
            trampoline shouldContain "${callbackType}Trampoline::class.java"
            trampoline shouldContain "Linker.nativeLinker().upcallStub(methodHandle, descriptor, Arena.global())"
            trampoline.split("Arena.global()").size shouldBe 2
            trampoline.split("upcallStub(").size shouldBe 2
            trampoline shouldContain "CallbackRuntime.dispatchSafely("
            trampoline shouldContain "type = ${callbackType}Type,"
            trampoline shouldContain "catch (failure: Throwable)"
            trampoline shouldContain "CallbackRuntime.reportUnroutedFailure(failure)"
        }
        jvm shouldContain "userdata = userdata2.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress),"
        jvm shouldContain "type = NoUserdataCallbackType,\n                userdata = null,"
        jvm shouldNotContain "Arena.ofShared()"
        jvm shouldNotContain ".bindTo("
    }

    "Native callbacks use top-level static trampolines and runtime routing" {
        val native = generateKmp(genericCallbacks).getValue("nativeMain")

        native shouldContain "private val SampleCallbackTrampoline = staticCFunction<UInt, COpaquePointer?, COpaquePointer?, Unit>"
        native shouldContain "private val NoUserdataCallbackTrampoline = staticCFunction<UInt, Unit>"
        listOf("SampleCallback", "NoUserdataCallback").forEach { callbackType ->
            native shouldContain
                "@OptIn(CallbackRuntimeApi::class)\nprivate val ${callbackType}Trampoline = staticCFunction"
            val trampoline = native
                .substringAfter("private val ${callbackType}Trampoline = staticCFunction")
                .substringBefore("\n}\n")
            trampoline shouldContain "CallbackRuntime.dispatchSafely("
            trampoline shouldContain "type = ${callbackType}Type,"
            trampoline shouldContain "catch (failure: Throwable)"
            trampoline shouldContain "CallbackRuntime.reportUnroutedFailure(failure)"
        }
        native shouldContain "userdata = userdata2?.let(::NativeAddress),"
        native shouldContain "type = NoUserdataCallbackType,\n            userdata = null,"
        native.split("staticCFunction<").size shouldBe 3
        native shouldNotContain "private var SampleCallback_callback"
        native shouldNotContain "private var NoUserdataCallback_callback"
        native shouldNotContain "mutableMapOf<NativeAddress"
        native shouldNotContain "Map<NativeAddress"
    }

    "large enums use one normalized raw ABI carrier on JVM and Native" {
        val generated = generateKmp(abiCallbacks)
        val common = generated.getValue("commonMain")
        val jvm = generated.getValue("jvmMain")
        val native = generated.getValue("nativeMain")

        common shouldContain "typealias LargeStatus = ULong"
        common shouldContain "const val LargeStatus_High : LargeStatus = 4294967296uL"
        jvm shouldContain
            "private val descriptor: FunctionDescriptor = FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, SamplePayload.layout, ValueLayout.ADDRESS, ValueLayout.ADDRESS)"
        jvm shouldContain """
            private fun invoke(
                status: Long,
                options: Long,
                payload: MemorySegment,
                device: MemorySegment,
                userdata: MemorySegment,
            )
        """.trimIndent().prependIndent("    ").trimStart()
        jvm shouldContain "status.toULong() as LargeStatus,"
        jvm shouldContain "LargeOptions(options),"

        native shouldContain
            "staticCFunction<ULong, ULong, CValue<webgpu.native.SamplePayload>, COpaquePointer?, COpaquePointer?, Unit> { status, options, payload, device, userdata ->"
        native shouldContain "status.toULong() as LargeStatus,"
        native shouldContain "LargeOptions(options.toLong()),"
    }

    "unsigned narrow options zero-extend into the application Long" {
        val generated = generateKmp(abiCallbacks)

        generated.getValue("jvmMain") shouldContain
            "private val descriptor: FunctionDescriptor = FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)"
        generated.getValue("jvmMain") shouldContain
            "callback.invoke(NarrowOptions(options.toUInt().toLong()))"
        generated.getValue("nativeMain") shouldContain
            "staticCFunction<UInt, COpaquePointer?, Unit> { options, userdata ->"
        generated.getValue("nativeMain") shouldContain
            "callback.invoke(NarrowOptions(options.toLong()))"
    }

    "struct-by-value callback parameters keep raw carriers until post-claim conversion" {
        val generated = generateKmp(abiCallbacks)

        generated.getValue("jvmMain") shouldContain "SamplePayload(NativeAddress(payload)),"
        generated.getValue("nativeMain") shouldContain "SamplePayload.ByValue(payload),"
    }

    "pointer-to-opaque-handle callback parameters load the handle slot post-claim" {
        val generated = generateKmp(abiCallbacks)

        generated.getValue("jvmMain") shouldContain
            "device.takeIf { it != MemorySegment.NULL }?.reinterpret(ValueLayout.ADDRESS.byteSize())?.get(ValueLayout.ADDRESS, 0L)?.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)?.let { WGPUDevice(it) },"
        generated.getValue("nativeMain") shouldContain
            "device?.reinterpret<COpaquePointerVar>()?.pointed?.value?.let(::NativeAddress)?.let { WGPUDevice(it) },"
    }

    "platform trampolines preserve the analyzed routing userdata position" {
        val generated = generateKmp(
            """
                typedef void (*InterleavedCallback)(
                    void * userdata9,
                    unsigned int value,
                    void * userdata1
                );
            """.trimIndent(),
        )

        generated.getValue("jvmMain") shouldContain
            "private fun invoke(\n        userdata9: MemorySegment,\n        value: Int,\n        userdata1: MemorySegment,\n    )"
        generated.getValue("nativeMain") shouldContain
            "staticCFunction<COpaquePointer?, UInt, COpaquePointer?, Unit> { userdata9, value, userdata1 ->"
    }

    "zero-argument Native callbacks emit a valid static lambda" {
        val native = generateKmp("typedef void (*EmptyCallback)(void);").getValue("nativeMain")

        native shouldContain "private val EmptyCallbackTrampoline = staticCFunction<Unit> {"
        native shouldNotContain "staticCFunction<Unit> {  ->"
    }

    "Android callback registration fails before allocating unsupported resources" {
        val android = generateKmp(genericCallbacks).getValue("androidMain")
        val unsupportedMessage =
            "Android/JNA callback registration is not supported; use raw bindings or an Android Native target"

        listOf("SampleCallback", "NoUserdataCallback").forEach { callbackType ->
            android shouldContain "actual fun ${callbackType}.Companion.register("
            android shouldContain "internal actual fun ${callbackType}.Companion.prepare("
        }
        android shouldContain "actual fun NoUserdataCallback.Companion.rearmAfterNativeQuiescence("
        android shouldContain "throw UnsupportedOperationException("
        android.split(unsupportedMessage).size shouldBe 6
        android shouldNotContain "CallbackRuntime.register("
        android shouldNotContain "CallbackRuntime.prepare("
        android shouldNotContain "Arena."
        android shouldNotContain "com.sun.jna.Callback"
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
