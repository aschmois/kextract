package org.graphiks.kextract.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.graphiks.kextract.callbacks.CallbackBindingsConfig
import org.graphiks.kextract.callbacks.CallbackInfoBinding
import org.graphiks.kextract.callbacks.CallbackInfoLifetime
import org.graphiks.kextract.callbacks.CallbackInfoMode
import org.graphiks.kextract.callbacks.CallbackInfoOwner
import org.graphiks.kextract.callbacks.DirectFunctionBinding
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.Logger
import org.graphiks.kextract.pipeline.Options
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files

class CallbackGeneratorIntegrationTest : FreeSpec({
    fun generateKmp(
        header: String,
        callbackBindings: CallbackBindingsConfig? = null,
    ): Map<String, String> {
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
                    callbackBindings = callbackBindings,
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

    fun generateKmpFailure(header: String): String {
        val input = Files.createTempFile("kextract-invalid-callback-carrier", ".h")
        val output = Files.createTempDirectory("kextract-invalid-callback-carrier-out")
        val errors = ByteArrayOutputStream()
        return try {
            input.toFile().writeText(header)
            KextractTool(
                Logger(
                    PrintWriter(ByteArrayOutputStream(), true),
                    PrintWriter(errors, true),
                ),
            ).runGeneration(
                listOf(input.toString()),
                Options(outputDir = output.toString(), multiplatform = true),
            ) shouldBe KextractTool.FAILURE
            errors.toString()
        } finally {
            input.toFile().delete()
            output.toFile().deleteRecursively()
        }
    }

    fun compileGeneratedJvmFixture(commonSource: String, jvmSource: String) {
        val workspace = Files.createTempDirectory("kextract-callback-name-classes")
        try {
            val common = workspace.resolve("callbackNamesCommon.kt")
            val jvm = workspace.resolve("callbackNamesJvm.kt")
            val kffiCommon = workspace.resolve("kffiCommon.kt")
            val kffiJvm = workspace.resolve("kffiJvm.kt")
            val output = Files.createDirectories(workspace.resolve("classes"))
            common.toFile().writeText(commonSource)
            jvm.toFile().writeText(jvmSource)
            kffiCommon.toFile().writeText(
                """
                package io.ygdrasil.kffi

                expect class NativeAddress
                interface Callback
                enum class CallbackPolicy { ONCE, REPEATING }
                fun interface CallbackExceptionHandler {
                    fun onException(error: Throwable)
                    companion object {
                        val Default = CallbackExceptionHandler { }
                    }
                }
                interface CallbackRegistration<C : Callback> : AutoCloseable {
                    val callback: NativeAddress
                    val userdata: NativeAddress?
                }
                @RequiresOptIn
                annotation class CallbackRuntimeApi
                @RequiresOptIn
                annotation class UnsafeCallbackRearmApi
                @CallbackRuntimeApi
                class CallbackType<C : Callback>(
                    val canonicalId: String,
                    val hasRoutingUserdata: Boolean,
                )
                @CallbackRuntimeApi
                class PreparedCallbackRegistration<C : Callback>
                @OptIn(CallbackRuntimeApi::class)
                object CallbackRuntime {
                    fun <C : Callback> register(
                        type: CallbackType<C>,
                        trampoline: NativeAddress,
                        policy: CallbackPolicy,
                        onError: CallbackExceptionHandler,
                        callback: C,
                    ): CallbackRegistration<C> = error("fixture")
                    fun <C : Callback> prepare(
                        type: CallbackType<C>,
                        trampoline: NativeAddress,
                        policy: CallbackPolicy,
                        onError: CallbackExceptionHandler,
                        callback: C,
                    ): PreparedCallbackRegistration<C> = error("fixture")
                    fun <C : Callback> rearmAfterNativeQuiescence(
                        type: CallbackType<C>,
                        trampoline: NativeAddress,
                        policy: CallbackPolicy,
                        onError: CallbackExceptionHandler,
                        callback: C,
                    ): CallbackRegistration<C> = error("fixture")
                    fun <C : Callback> activateForNativeCall(
                        prepared: PreparedCallbackRegistration<C>,
                        call: (CallbackRegistration<C>) -> Unit,
                    ): CallbackRegistration<C> = error("fixture")
                    fun <C : Callback> dispatchSafely(
                        type: CallbackType<C>,
                        userdata: NativeAddress?,
                        call: (C) -> Unit,
                    ) = Unit
                    fun reportUnroutedFailure(failure: Throwable) = Unit
                }
                expect value class CString(val handler: NativeAddress)
                @JvmInline
                value class ArrayHolder<T>(val handler: NativeAddress)
                expect class MemoryAllocator()
                interface CStructure {
                    val handler: NativeAddress
                }
                """.trimIndent(),
            )
            kffiJvm.toFile().writeText(
                """
                package io.ygdrasil.kffi

                import java.lang.foreign.MemorySegment

                class JvmNativeAddress(val handler: MemorySegment)
                actual typealias NativeAddress = JvmNativeAddress
                @JvmInline
                actual value class CString actual constructor(actual val handler: NativeAddress)
                actual class MemoryAllocator actual constructor()
                fun findOrThrow(name: String): MemorySegment = MemorySegment.NULL
                """.trimIndent(),
            )

            K2JVMCompiler().exec(
                System.err,
                "-no-stdlib",
                "-no-reflect",
                "-Xmulti-platform",
                "-Xcommon-sources=$common,$kffiCommon",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                output.toString(),
                common.toString(),
                jvm.toString(),
                kffiCommon.toString(),
                kffiJvm.toString(),
            ) shouldBe ExitCode.OK
        } finally {
            workspace.toFile().deleteRecursively()
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
        typedef enum LargeStatus : unsigned long long {
            LargeStatus_Zero = 0,
            LargeStatus_High = 0x100000000ULL
        } LargeStatus;

        typedef enum LargeOptions : unsigned long long {
            LargeOptions_None = 0,
            LargeOptions_High = 0x100000000ULL
        } LargeOptions;

        typedef enum NarrowOptions : unsigned int {
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

    "callback names are valid and collision-free in every generated target" {
        val config = CallbackBindingsConfig().also { bindings ->
            bindings.directFunctionBindings = listOf(
                DirectFunctionBinding().also { binding ->
                    binding.function = "function:set_class_callback"
                    binding.callbackParameter = "callback"
                    binding.callbackType = "typedef:class"
                    binding.routingUserdataParameter = "userdata"
                },
            )
        }
        val generated = generateKmp(
            """
                typedef void (*class)(int callback,
                                      int failure,
                                      int policy,
                                      int onError,
                                      int fun,
                                      int fun_,
                                      void *userdata);
                void set_class_callback(int policy, class callback, void *userdata);
            """.trimIndent(),
            config,
        )
        val common = generated.getValue("commonMain")
        val jvm = generated.getValue("jvmMain")
        val native = generated.getValue("nativeMain")
        val android = generated.getValue("androidMain")

        common shouldContain "fun interface class_ : Callback"
        common shouldContain "fun_: Int,"
        common shouldContain "fun__2: Int,"
        common shouldContain "canonicalId = \"typedef:class\""
        common shouldContain "policy_2: Int,"
        common shouldContain "callback: class_,"
        common shouldContain """
            fun set_class_callback(
                policy_2: Int,
                policy: CallbackPolicy,
                onError: CallbackExceptionHandler = CallbackExceptionHandler.Default,
                callback: class_,
            ): CallbackRegistration<class_>
        """.trimIndent()
        common shouldNotContain "policy: Int,\n    policy: CallbackPolicy,"
        jvm shouldContain "private object class_Trampoline"
        jvm shouldContain "actual fun class_.Companion.register("
        jvm shouldContain "findOrThrow(\"set_class_callback\")"
        native shouldContain "private val class_Trampoline = staticCFunction"
        native shouldContain "actual fun class_.Companion.register("
        android shouldContain "actual fun class_.Companion.register("
        listOf(common, jvm, native).forEach { source ->
            source shouldContain "fun_"
            source shouldContain "fun__2"
        }

        compileGeneratedJvmFixture(common, jvm)
    }

    "configured direct callback helpers are transactional on every platform" {
        val config = CallbackBindingsConfig().also { bindings ->
            bindings.directFunctionBindings = listOf(
                DirectFunctionBinding().also { binding ->
                    binding.function = "function:sample_set_callback"
                    binding.callbackParameter = "callback"
                    binding.callbackType = "typedef:SampleCallback"
                    binding.routingUserdataParameter = "userdata2"
                },
                DirectFunctionBinding().also { binding ->
                    binding.function = "function:sample_set_no_userdata_callback"
                    binding.callbackParameter = "callback"
                    binding.callbackType = "typedef:NoUserdataCallback"
                },
            )
        }
        val generated = generateKmp(
            """
                typedef void (*SampleCallback)(unsigned int value, void * userdata1, void * userdata2);
                typedef void (*NoUserdataCallback)(unsigned int value);
                void sample_set_callback(unsigned int limit, SampleCallback callback, void * userdata2);
                void sample_set_no_userdata_callback(unsigned int limit, NoUserdataCallback callback);
            """.trimIndent(),
            config,
        )
        val common = generated.getValue("commonMain")
        val jvm = generated.getValue("jvmMain")
        val native = generated.getValue("nativeMain")
        val android = generated.getValue("androidMain")

        common shouldContain "internal expect fun sample_set_callbackCallbackBindingPreflight()"
        common shouldContain """
            fun sample_set_callback(
                limit: UInt,
                policy: CallbackPolicy,
                onError: CallbackExceptionHandler = CallbackExceptionHandler.Default,
                callback: SampleCallback,
            ): CallbackRegistration<SampleCallback> {
        """.trimIndent()
        val safeSetter = common
            .substringAfter("fun sample_set_callback(\n    limit: UInt,\n    policy: CallbackPolicy,")
            .substringBefore("\n}\n")
        safeSetter shouldContain "val validatedLimit = limit"
        safeSetter shouldContain "sample_set_callbackCallbackBindingPreflight()"
        safeSetter shouldContain "val prepared = SampleCallback.prepare("
        safeSetter shouldContain "return CallbackRuntime.activateForNativeCall(prepared) { registration ->"
        safeSetter shouldContain "sample_set_callback(validatedLimit, registration.callback, registration.userdata)"
        safeSetter shouldNotContain "userdata2: NativeAddress?"
        (safeSetter.indexOf("val validatedLimit = limit") <
            safeSetter.indexOf("sample_set_callbackCallbackBindingPreflight()")) shouldBe true
        (safeSetter.indexOf("sample_set_callbackCallbackBindingPreflight()") <
            safeSetter.indexOf("val prepared = SampleCallback.prepare(")) shouldBe true
        (safeSetter.indexOf("activateForNativeCall") <
            safeSetter.indexOf("sample_set_callback(validatedLimit, registration.callback, registration.userdata)")) shouldBe true

        common shouldContain """
            @UnsafeCallbackRearmApi
            fun rearmAfterNativeQuiescence(
        """.trimIndent()
        common shouldNotContain "rearmAfterNativeQuiescence: Boolean"
        common shouldNotContain "allowRearm"

        jvm shouldContain """
            internal actual fun sample_set_callbackCallbackBindingPreflight() {
                val address = sample_set_callback_ADDR
                val handle = sample_set_callback_HANDLE
            }
        """.trimIndent()
        native shouldContain "internal actual fun sample_set_callbackCallbackBindingPreflight() = Unit"
        android shouldContain "internal actual fun sample_set_callbackCallbackBindingPreflight()"
        android shouldContain "throw UnsupportedOperationException("
        android shouldNotContain "CallbackRuntime.activateForNativeCall"
    }

    "configured callback-info factory enforces the mode allowlist before allocation" {
        val config = CallbackBindingsConfig().also { bindings ->
            bindings.callbackInfoBindings = listOf(
                CallbackInfoBinding().also { binding ->
                    binding.struct = "struct:WGPUQueueWorkDoneCallbackInfo"
                    binding.owner = CallbackInfoOwner().also { owner ->
                        owner.function = "function:wgpuQueueOnSubmittedWorkDone"
                        owner.parameterPath = "callbackInfo"
                        owner.lifetime = CallbackInfoLifetime.CONSUMED_DURING_CALL
                    }
                    binding.callbackField = "callback"
                    binding.callbackType = "typedef:WGPUQueueWorkDoneCallback"
                    binding.routingUserdataField = "userdata2"
                    binding.applicationUserdataFields = listOf("userdata1")
                    binding.mode = CallbackInfoMode().also { mode ->
                        mode.field = "mode"
                        mode.type = "typedef:WGPUCallbackMode"
                        mode.allowedConstants = listOf(
                            "constant:WGPUCallbackMode_WaitAnyOnly",
                            "constant:WGPUCallbackMode_AllowProcessEvents",
                            "constant:WGPUCallbackMode_AllowSpontaneous",
                        )
                    }
                },
            )
        }
        val common = generateKmp(
            """
                typedef unsigned int WGPUCallbackMode;
                const WGPUCallbackMode WGPUCallbackMode_Undefined = 0;
                const WGPUCallbackMode WGPUCallbackMode_WaitAnyOnly = 1;
                const WGPUCallbackMode WGPUCallbackMode_AllowProcessEvents = 2;
                const WGPUCallbackMode WGPUCallbackMode_AllowSpontaneous = 3;
                const WGPUCallbackMode WGPUCallbackMode_Force32 = 0x7fffffff;

                typedef void (*WGPUQueueWorkDoneCallback)(
                    unsigned int status,
                    void * userdata1,
                    void * userdata2
                );
                typedef struct WGPUQueueWorkDoneCallbackInfo {
                    WGPUCallbackMode mode;
                    WGPUQueueWorkDoneCallback callback;
                    void * userdata1;
                    void * userdata2;
                } WGPUQueueWorkDoneCallbackInfo;
                void wgpuQueueOnSubmittedWorkDone(WGPUQueueWorkDoneCallbackInfo callbackInfo);
            """.trimIndent(),
            config,
        ).getValue("commonMain")

        common shouldContain """
            fun WGPUQueueWorkDoneCallbackInfo.Companion.allocate(
                allocator: MemoryAllocator,
                mode: WGPUCallbackMode,
                registration: CallbackRegistration<WGPUQueueWorkDoneCallback>,
                userdata1: NativeAddress? = null,
            ): WGPUQueueWorkDoneCallbackInfo
        """.trimIndent()
        val factory = common
            .substringAfter("fun WGPUQueueWorkDoneCallbackInfo.Companion.allocate(\n")
            .substringBefore("\n}\n")
        factory shouldContain "require("
        factory shouldContain "mode == WGPUCallbackMode_WaitAnyOnly ||"
        factory shouldContain "mode == WGPUCallbackMode_AllowProcessEvents ||"
        factory shouldContain "mode == WGPUCallbackMode_AllowSpontaneous,"
        factory shouldNotContain "WGPUCallbackMode_Undefined"
        factory shouldNotContain "WGPUCallbackMode_Force32"
        factory shouldContain "val info = allocate(allocator)"
        factory shouldContain "info.callback = registration.callback"
        factory shouldContain "info.userdata2 = registration.userdata"
        factory shouldContain "info.userdata1 = userdata1"
        (factory.indexOf("require(") < factory.indexOf("val info = allocate(allocator)")) shouldBe true
        common shouldContain "fun allocate(allocator: MemoryAllocator): WGPUQueueWorkDoneCallbackInfo"
    }

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

    "signed long callback scalars fail with a target-independent diagnostic" {
        generateKmpFailure("typedef void (*LongCallback)(long value);") shouldContain
            "Unsupported multiplatform callback C ABI scalar 'long': " +
            "target-dependent width (LP64 vs LLP64); use a fixed-width C integer type"
    }

    "unsigned long callback scalars fail with the same target-independent diagnostic" {
        generateKmpFailure("typedef void (*UnsignedLongCallback)(unsigned long value);") shouldContain
            "Unsupported multiplatform callback C ABI scalar 'long': " +
            "target-dependent width (LP64 vs LLP64); use a fixed-width C integer type"
    }

    "long double callback scalars fail with a target-independent diagnostic" {
        generateKmpFailure("typedef void (*LongDoubleCallback)(long double value);") shouldContain
            "Unsupported multiplatform callback C ABI scalar 'long double': " +
            "target-dependent size and format; use double or an explicit fixed-width representation"
    }

    "fixed-width callback scalars retain stable JVM and Native carriers" {
        val generated = generateKmp(
            """
                typedef void (*StableCallback)(long long signed_value,
                                               unsigned long long unsigned_value,
                                               double floating_value);
            """.trimIndent(),
        )

        generated.getValue("jvmMain") shouldContain
            "FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE)"
        generated.getValue("nativeMain") shouldContain
            "staticCFunction<Long, ULong, Double, Unit>"
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
