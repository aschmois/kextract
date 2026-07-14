package org.graphiks.kextract.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.graphiks.kextract.callbacks.CallbackBindingsConfig
import org.graphiks.kextract.callbacks.DirectFunctionBinding
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.Logger
import org.graphiks.kextract.pipeline.Options
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.net.URLClassLoader
import java.nio.file.Files

class KmpJvmDirectCallbackTransactionTest : FreeSpec({
    "throwing JVM carrier conversion happens before callback preparation or symbol resolution" {
        val generated = generateDirectCallbackFixture()
        val result = compileAndRunTransactionProbe(generated)

        result.toList() shouldBe listOf(
            1, // SentinelConversionFailure was the observed failure.
            0, // CallbackRuntime.prepare was never entered.
            0, // findOrThrow was never entered.
        )
    }
})

private data class GeneratedJvmFixture(
    val common: String,
    val jvm: String,
)

private fun generateDirectCallbackFixture(): GeneratedJvmFixture {
    val input = Files.createTempFile("kextract-jvm-direct-callback-transaction", ".h")
    val output = Files.createTempDirectory("kextract-jvm-direct-callback-transaction-out")
    val bindings = CallbackBindingsConfig().also { config ->
        config.directFunctionBindings = listOf(
            DirectFunctionBinding().also { binding ->
                binding.function = "function:sample_set_callback"
                binding.callbackParameter = "callback"
                binding.callbackType = "typedef:SampleCallback"
                binding.routingUserdataParameter = "userdata"
            },
        )
    }
    return try {
        input.toFile().writeText(
            """
            typedef struct SamplePayload { int value; } SamplePayload;
            typedef void (*SampleCallback)(void *userdata);
            void sample_set_callback(
                SamplePayload payload,
                SampleCallback callback,
                void *userdata
            );
            """.trimIndent(),
        )
        KextractTool(Logger.DEFAULT).runGeneration(
            listOf(input.toString()),
            Options(
                targetPackage = "sample.bindings",
                outputDir = output.toString(),
                multiplatform = true,
                callbackBindings = bindings,
            ),
        ) shouldBe KextractTool.SUCCESS

        fun readSourceSet(name: String): String = Files.walk(output.resolve(name)).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .map { it.toFile().readText() }
                .toList()
                .joinToString("\n")
        }

        GeneratedJvmFixture(
            common = readSourceSet("commonMain"),
            jvm = readSourceSet("jvmMain"),
        )
    } finally {
        input.toFile().delete()
        output.toFile().deleteRecursively()
    }
}

private fun compileAndRunTransactionProbe(generated: GeneratedJvmFixture): IntArray {
    val workspace = Files.createTempDirectory("kextract-jvm-direct-callback-transaction-classes")
    return try {
        val common = workspace.resolve("sampleCommon.kt")
        val jvm = workspace.resolve("sampleJvm.kt")
        val kffiCommon = workspace.resolve("kffiCommon.kt")
        val kffiJvm = workspace.resolve("kffiJvm.kt")
        val probe = workspace.resolve("probe.kt")
        val output = Files.createDirectories(workspace.resolve("classes"))

        common.toFile().writeText(generated.common)
        jvm.toFile().writeText(generated.jvm)
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
                var prepareCount: Int = 0
                var symbolResolutionCount: Int = 0

                fun <C : Callback> register(
                    type: CallbackType<C>,
                    trampoline: NativeAddress,
                    policy: CallbackPolicy,
                    onError: CallbackExceptionHandler,
                    callback: C,
                ): CallbackRegistration<C> = error("registration reached")

                fun <C : Callback> prepare(
                    type: CallbackType<C>,
                    trampoline: NativeAddress,
                    policy: CallbackPolicy,
                    onError: CallbackExceptionHandler,
                    callback: C,
                ): PreparedCallbackRegistration<C> {
                    prepareCount += 1
                    return PreparedCallbackRegistration()
                }

                fun <C : Callback> rearmAfterNativeQuiescence(
                    type: CallbackType<C>,
                    trampoline: NativeAddress,
                    policy: CallbackPolicy,
                    onError: CallbackExceptionHandler,
                    callback: C,
                ): CallbackRegistration<C> = error("rearm reached")

                fun <C : Callback> activateForNativeCall(
                    prepared: PreparedCallbackRegistration<C>,
                    call: (CallbackRegistration<C>) -> Unit,
                ): CallbackRegistration<C> = error("activation reached")

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

            import java.lang.foreign.Arena
            import java.lang.foreign.MemorySegment

            class JvmNativeAddress(val handler: MemorySegment)
            actual typealias NativeAddress = JvmNativeAddress
            @JvmInline
            actual value class CString actual constructor(actual val handler: NativeAddress)
            actual class MemoryAllocator actual constructor() {
                fun allocate(byteSize: Long): NativeAddress = NativeAddress(Arena.global().allocate(byteSize))
            }
            fun findOrThrow(name: String): MemorySegment {
                CallbackRuntime.symbolResolutionCount += 1
                return MemorySegment.NULL
            }
            """.trimIndent(),
        )
        probe.toFile().writeText(
            """
            package sample.probe

            import io.ygdrasil.kffi.CallbackPolicy
            import io.ygdrasil.kffi.CallbackRuntime
            import io.ygdrasil.kffi.NativeAddress
            import sample.bindings.SampleCallback
            import sample.bindings.SamplePayload
            import sample.bindings.sample_set_callback

            class SentinelConversionFailure : RuntimeException()

            fun runProbe(): IntArray {
                CallbackRuntime.prepareCount = 0
                CallbackRuntime.symbolResolutionCount = 0
                val payload = object : SamplePayload {
                    override var value: Int = 7
                    override val handler: NativeAddress
                        get() = throw SentinelConversionFailure()
                }
                var caughtSentinel = 0
                try {
                    sample_set_callback(
                        payload = payload,
                        policy = CallbackPolicy.ONCE,
                        callback = SampleCallback { },
                    )
                } catch (_: SentinelConversionFailure) {
                    caughtSentinel = 1
                }
                return intArrayOf(
                    caughtSentinel,
                    CallbackRuntime.prepareCount,
                    CallbackRuntime.symbolResolutionCount,
                )
            }
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
            probe.toString(),
        ) shouldBe ExitCode.OK

        URLClassLoader(
            arrayOf(output.toUri().toURL()),
            KmpJvmDirectCallbackTransactionTest::class.java.classLoader,
        ).use { classLoader ->
            classLoader.loadClass("sample.probe.ProbeKt")
                .getMethod("runProbe")
                .invoke(null) as IntArray
        }
    } finally {
        workspace.toFile().deleteRecursively()
    }
}
