package org.graphiks.kextract.integration

import com.sun.jna.Pointer
import com.sun.jna.Structure
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.graphiks.kextract.Declaration
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.Logger
import org.graphiks.kextract.pipeline.Options
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLClassLoader

private data class AndroidSources(val common: String, val bridge: String, val jna: String)

private fun generateAndroidSources(header: String): AndroidSources {
    val workspace = Files.createTempDirectory("kextract-kmp-android-jna")
    val input = workspace.resolve("wgpu.h")
    val output = workspace.resolve("out")
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

        AndroidSources(
            common = output.resolve("commonMain/kotlin/sample/bindings/wgpu_hCommon.kt").toFile().readText(),
            bridge = output.resolve("androidMain/kotlin/sample/bindings/wgpu_hAndroid.kt").toFile().readText(),
            jna = output.resolve("androidMain/kotlin/sample/bindings/android/wgpu_h.kt").toFile().readText(),
        )
    } finally {
        workspace.toFile().deleteRecursively()
    }
}

private val RECORD_STORAGE_HEADER =
    """
    typedef struct WGPUValue {
        int value;
    } WGPUValue;

    typedef struct WGPUContainer {
        WGPUValue inlineValue;
        WGPUValue* pointerValue;
    } WGPUContainer;
    """.trimIndent()

private val TYPEDEF_ALIAS_HEADER =
    """
    typedef struct WGPUValue {
        int value;
    } WGPUValue;

    typedef WGPUValue WGPUAlias;

    typedef struct WGPUAliasContainer {
        WGPUAlias inlineAlias;
        WGPUAlias* pointerAlias;
    } WGPUAliasContainer;
    """.trimIndent()

private val NATIVE_DISPLAY_HEADER =
    """
    typedef enum WGPUNativeDisplayHandleType {
        WGPUNativeDisplayHandleType_None = 0,
        WGPUNativeDisplayHandleType_Xlib = 1,
        WGPUNativeDisplayHandleType_Xcb = 2,
        WGPUNativeDisplayHandleType_Wayland = 3
    } WGPUNativeDisplayHandleType;

    typedef struct WGPUXlibDisplayHandle {
        void* display;
        int screen;
    } WGPUXlibDisplayHandle;

    typedef struct WGPUXcbDisplayHandle {
        void* connection;
        int screen;
    } WGPUXcbDisplayHandle;

    typedef struct WGPUWaylandDisplayHandle {
        void* display;
    } WGPUWaylandDisplayHandle;

    typedef struct WGPUNativeDisplayHandle {
        WGPUNativeDisplayHandleType type;
        union {
            WGPUXlibDisplayHandle xlib;
            WGPUXcbDisplayHandle xcb;
            WGPUWaylandDisplayHandle wayland;
        } data;
    } WGPUNativeDisplayHandle;

    typedef struct WGPUInstanceExtras {
        WGPUNativeDisplayHandle displayHandle;
    } WGPUInstanceExtras;
    """.trimIndent()

private val ABI_HEADER =
    """
    typedef unsigned int WGPUBlendOperation;
    typedef unsigned int WGPUBlendFactor;
    typedef unsigned int WGPUCallbackMode;
    typedef unsigned int WGPUFeatureName;

    typedef struct WGPUBlendComponent {
        WGPUBlendOperation operation;
        WGPUBlendFactor srcFactor;
        WGPUBlendFactor dstFactor;
    } WGPUBlendComponent;

    typedef struct WGPUBlendState {
        WGPUBlendComponent color;
        WGPUBlendComponent alpha;
    } WGPUBlendState;

    typedef struct WGPUChainedStruct {
        void* next;
    } WGPUChainedStruct;

    typedef struct WGPUStringView {
        const char* data;
        unsigned long long length;
    } WGPUStringView;

    typedef struct WGPUQueueDescriptor {
        WGPUChainedStruct* nextInChain;
        WGPUStringView label;
    } WGPUQueueDescriptor;

    typedef void (*WGPUDeviceLostCallback)(void);
    typedef void (*WGPUUncapturedErrorCallback)(void);

    typedef struct WGPUDeviceLostCallbackInfo {
        WGPUChainedStruct* nextInChain;
        WGPUCallbackMode mode;
        WGPUDeviceLostCallback callback;
        void* userdata1;
        void* userdata2;
    } WGPUDeviceLostCallbackInfo;

    typedef struct WGPUUncapturedErrorCallbackInfo {
        WGPUChainedStruct* nextInChain;
        WGPUUncapturedErrorCallback callback;
        void* userdata1;
        void* userdata2;
    } WGPUUncapturedErrorCallbackInfo;

    typedef struct WGPULimits {
        unsigned int maxTextureDimension1D;
    } WGPULimits;

    typedef struct WGPUDeviceDescriptor {
        WGPUChainedStruct* nextInChain;
        WGPUStringView label;
        unsigned long long requiredFeatureCount;
        const WGPUFeatureName* requiredFeatures;
        const WGPULimits* requiredLimits;
        WGPUQueueDescriptor defaultQueue;
        WGPUDeviceLostCallbackInfo deviceLostCallbackInfo;
        WGPUUncapturedErrorCallbackInfo uncapturedErrorCallbackInfo;
    } WGPUDeviceDescriptor;

    typedef union WGPUScalar {
        unsigned int u32;
        double f64;
        _Bool boolean;
    } WGPUScalar;

    typedef struct WGPUUnionContainer {
        WGPUScalar scalar;
        WGPUScalar* scalarPointer;
    } WGPUUnionContainer;
    """.trimIndent()

private val NATIVE_DISPLAY_COPY_PROBE =
    """
    package sample.probe

    import com.sun.jna.Pointer

    fun runProbe(): LongArray {
        val rawXlib = sample.bindings.android.WGPUXlibDisplayHandle.ByValue().apply {
            display = Pointer(0x1234)
            screen = 7
        }
        val xlib = sample.bindings.WGPUXlibDisplayHandle.ByValue(rawXlib)
        val display: sample.bindings.WGPUNativeDisplayHandle = sample.bindings.WGPUNativeDisplayHandle.ByValue(
            sample.bindings.android.WGPUNativeDisplayHandle.ByValue(),
        )
        display.setXlib(xlib)

        val extras = sample.bindings.WGPUInstanceExtras.ByValue(
            sample.bindings.android.WGPUInstanceExtras.ByValue(),
        )
        extras.displayHandle = display

        val copied = extras.displayHandle.xlib ?: error("missing copied Xlib payload")
        return longArrayOf(Pointer.nativeValue(copied.display ?: Pointer.NULL), copied.screen.toLong())
    }
    """.trimIndent()

private val GENERAL_UNION_COPY_PROBE =
    """
    package sample.probe

    fun runProbe(): LongArray {
        val scalar: sample.bindings.WGPUScalar =
            sample.bindings.WGPUScalar.ByValue(sample.bindings.android.WGPUScalar.ByValue())
        scalar.u32 = 0x12345678u

        val container = sample.bindings.WGPUUnionContainer.ByValue(
            sample.bindings.android.WGPUUnionContainer.ByValue(),
        )
        container.scalar = scalar

        return longArrayOf(container.scalar.u32.toLong())
    }
    """.trimIndent()

private class CompiledJnaSources(
    private val workspace: Path,
    private val classLoader: URLClassLoader,
) : AutoCloseable {
    fun structure(name: String): Structure {
        val type = classLoader.loadClass("sample.bindings.android.$name")
        return type.getConstructor(Pointer::class.java).newInstance(null as Pointer?) as Structure
    }

    fun nestedStructure(owner: String, name: String): Structure {
        val type = classLoader.loadClass("sample.bindings.android.$owner\$$name")
        return type.getConstructor().newInstance() as Structure
    }

    override fun close() {
        classLoader.close()
        workspace.toFile().deleteRecursively()
    }
}

private fun compileGeneratedJna(source: String): CompiledJnaSources {
    val workspace = Files.createTempDirectory("kextract-generated-jna-classes")
    val input = workspace.resolve("wgpu_h.kt")
    val output = Files.createDirectories(workspace.resolve("classes"))
    input.toFile().writeText(source)

    val exitCode = K2JVMCompiler().exec(
        System.err,
        "-no-stdlib",
        "-no-reflect",
        "-classpath",
        System.getProperty("java.class.path"),
        "-d",
        output.toString(),
        input.toString(),
    )
    exitCode shouldBe ExitCode.OK
    return CompiledJnaSources(workspace, URLClassLoader(arrayOf(output.toUri().toURL()), Structure::class.java.classLoader))
}

private fun compileGeneratedAndroid(sources: AndroidSources, probe: String? = null): LongArray? {
    val workspace = Files.createTempDirectory("kextract-generated-android-classes")
    try {
        val common = workspace.resolve("wgpu_hCommon.kt")
        val bridge = workspace.resolve("wgpu_hAndroid.kt")
        val jna = workspace.resolve("wgpu_h.kt")
        val kffiCommon = workspace.resolve("kffiCommon.kt")
        val kffiAndroid = workspace.resolve("kffiAndroid.kt")
        val probeFile = probe?.let { workspace.resolve("probe.kt") }
        val output = Files.createDirectories(workspace.resolve("classes"))
        common.toFile().writeText(sources.common)
        bridge.toFile().writeText(sources.bridge)
        jna.toFile().writeText(sources.jna)
        kffiCommon.toFile().writeText(
            """
            package io.ygdrasil.kffi

            expect class NativeAddress
            expect class CallbackHolder<T> {
                val handler: NativeAddress
            }
            expect value class CString(val handler: NativeAddress)
            @JvmInline
            value class ArrayHolder<T>(val handler: NativeAddress)
            expect class MemoryAllocator()
            """.trimIndent(),
        )
        kffiAndroid.toFile().writeText(
            """
            package io.ygdrasil.kffi

            actual typealias NativeAddress = com.sun.jna.Pointer
            actual class CallbackHolder<T>(actual val handler: NativeAddress)
            @JvmInline
            actual value class CString actual constructor(actual val handler: NativeAddress)
            actual class MemoryAllocator actual constructor() {
                fun register(value: Any) = Unit
            }
            fun NativeAddress.toAddress(): Long = com.sun.jna.Pointer.nativeValue(this)
            """.trimIndent(),
        )
        probe?.let { probeFile?.toFile()?.writeText(it) }

        val arguments = mutableListOf(
            "-no-stdlib",
            "-no-reflect",
            "-Xmulti-platform",
            "-Xcommon-sources=${common},${kffiCommon}",
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            output.toString(),
            common.toString(),
            bridge.toString(),
            jna.toString(),
            kffiCommon.toString(),
            kffiAndroid.toString(),
        )
        probeFile?.let { arguments.add(it.toString()) }
        K2JVMCompiler().exec(System.err, *arguments.toTypedArray()) shouldBe ExitCode.OK

        return probeFile?.let {
            URLClassLoader(arrayOf(output.toUri().toURL()), Structure::class.java.classLoader).use { classLoader ->
                classLoader.loadClass("sample.probe.ProbeKt").getMethod("runProbe").invoke(null) as LongArray
            }
        }
    } finally {
        workspace.toFile().deleteRecursively()
    }
}

private fun Declaration.findScoped(name: String): Declaration.Scoped? = when (this) {
    is Declaration.Scoped -> if (name() == name) this else members().firstNotNullOfOrNull { it.findScoped(name) }
    else -> null
}

private fun assertMatchesClangLayout(
    parsed: Declaration.Scoped,
    name: String,
    jna: Structure,
    fields: List<Pair<String, String>>,
) {
    val clang = parsed.findScoped(name) ?: error("Missing Clang declaration for $name")
    jna.size().toLong() shouldBe clang.metric("ClangSizeOf", "getSize") / 8
    val fieldOffset = Structure::class.java.getDeclaredMethod("fieldOffset", String::class.java).apply {
        isAccessible = true
    }
    fields.forEach { (fieldName, expectedType) ->
        val field = clang.members().filterIsInstance<Declaration.Variable>().single { it.name() == fieldName }
        (fieldOffset.invoke(jna, fieldName) as Int).toLong() shouldBe field.metric("ClangOffsetOf", "getOffset") / 8
        jna.javaClass.getDeclaredField(fieldName).type.name shouldBe expectedType
    }

    val getFieldOrder = jna.javaClass.getDeclaredMethod("getFieldOrder").apply { isAccessible = true }
    getFieldOrder.invoke(jna) shouldBe fields.map { it.first }
}

private fun assertJnaUnionLayout(
    union: Structure,
    expectedSize: Int,
    fields: List<Pair<String, String>>,
) {
    union.size() shouldBe expectedSize
    val fieldOffset = Structure::class.java.getDeclaredMethod("fieldOffset", String::class.java).apply {
        isAccessible = true
    }
    fields.forEach { (fieldName, expectedType) ->
        fieldOffset.invoke(union, fieldName) shouldBe 0
        union.javaClass.getDeclaredField(fieldName).type.name shouldBe expectedType
    }
}

private fun Declaration.metric(attributeName: String, getterName: String): Long {
    val attribute = attributes().single { it.javaClass.simpleName == attributeName }
    return attribute.javaClass.getMethod(getterName).invoke(attribute) as Long
}

class KmpAndroidJnaAbiTest : FreeSpec({
    "inline record fields use initialized JNA ByValue storage" {
        val generated = generateAndroidSources(RECORD_STORAGE_HEADER)

        generated.jna shouldContain
            "@JvmField var inlineValue: sample.bindings.android.WGPUValue.ByValue = sample.bindings.android.WGPUValue.ByValue()"
    }

    "pointer-to-record fields never use JNA ByValue storage" {
        val generated = generateAndroidSources(RECORD_STORAGE_HEADER)

        generated.jna shouldContain "@JvmField var pointerValue: Pointer? = null"
        generated.jna shouldNotContain "pointerValue: sample.bindings.android.WGPUValue.ByValue"
    }

    "inline record wrappers copy values without a ByReference cast and synchronize the JNA field" {
        val generated = generateAndroidSources(RECORD_STORAGE_HEADER)

        generated.bridge shouldContain "handle.readField(\"inlineValue\")"
        generated.bridge shouldContain "return WGPUValue.ByValue(handle.inlineValue)"
        generated.bridge shouldContain "val bytes = value.handler.getByteArray(0, handle.inlineValue.size())"
        generated.bridge shouldContain "handle.inlineValue.pointer.write(0, bytes, 0, bytes.size)"
        generated.bridge shouldNotContain "value as WGPUValue.ByReference"
    }

    "typedef aliases use the generated canonical record class inline and stay pointers through indirection" {
        val generated = generateAndroidSources(TYPEDEF_ALIAS_HEADER)

        generated.jna shouldContain
            "@JvmField var inlineAlias: sample.bindings.android.WGPUValue.ByValue = sample.bindings.android.WGPUValue.ByValue()"
        generated.jna shouldContain "@JvmField var pointerAlias: Pointer? = null"
        generated.jna shouldNotContain "sample.bindings.android.WGPUAlias.ByValue"
        generated.bridge shouldContain "return WGPUValue.ByValue(handle.inlineAlias)"
    }

    "native display handle is embedded and synchronized by value in instance extras" {
        val generated = generateAndroidSources(NATIVE_DISPLAY_HEADER)

        generated.jna shouldContain
            "@JvmField var displayHandle: sample.bindings.android.WGPUNativeDisplayHandle.ByValue = sample.bindings.android.WGPUNativeDisplayHandle.ByValue()"
        generated.bridge shouldContain "handle.readField(\"displayHandle\")"
        generated.bridge shouldContain "return WGPUNativeDisplayHandle.ByValue(handle.displayHandle)"
        generated.bridge shouldContain
            "val bytes = value.handler.getByteArray(0, handle.displayHandle.size())"
        generated.bridge shouldContain "handle.displayHandle.pointer.write(0, bytes, 0, bytes.size)"
        generated.bridge shouldNotContain
            "sample.bindings.android.WGPUNativeDisplayHandle.ByValue(value.handler)"
        generated.bridge shouldNotContain "value as WGPUNativeDisplayHandle.ByReference"
    }

    "native display tagged union stores and copies its active member by value" {
        val generated = generateAndroidSources(NATIVE_DISPLAY_HEADER)

        generated.jna shouldContain "class Data : com.sun.jna.Union(), Structure.ByValue"
        generated.bridge shouldContain "handle.data.readField(\"xlib\")"
        generated.bridge shouldContain "return WGPUXlibDisplayHandle.ByValue(handle.data.xlib)"
        generated.bridge shouldContain
            "val copy = sample.bindings.android.WGPUXlibDisplayHandle.ByValue(value.handler)"
        generated.bridge shouldContain "copy.read()"
        generated.bridge shouldContain "handle.data.writeField(\"xlib\")"
        generated.bridge shouldContain "handle.writeField(\"data\")"
        generated.bridge shouldNotContain
            "return WGPUXlibDisplayHandle.ByReference(sample.bindings.android.WGPUXlibDisplayHandle.ByReference"
    }

    "union records use JNA Union and preserve inline versus pointer storage" {
        val generated = generateAndroidSources(ABI_HEADER)

        generated.jna shouldContain "open class WGPUScalar(pointer: Pointer? = null) : Union(pointer)"
        generated.jna shouldContain
            "@JvmField var scalar: sample.bindings.android.WGPUScalar.ByValue = sample.bindings.android.WGPUScalar.ByValue()"
        generated.jna shouldContain "@JvmField var scalarPointer: Pointer? = null"
        generated.bridge shouldContain "val bytes = value.handler.getByteArray(0, handle.scalar.size())"
        generated.bridge shouldContain "handle.scalar.pointer.write(0, bytes, 0, bytes.size)"
        generated.bridge shouldNotContain "sample.bindings.android.WGPUScalar.ByValue(value.handler)"
    }

    "union wrappers select and synchronize the active JNA field" {
        val generated = generateAndroidSources(ABI_HEADER)

        generated.bridge shouldContain "handle.readField(\"u32\")"
        generated.bridge shouldContain "return handle.u32.toUInt() as UInt"
        generated.bridge shouldContain "handle.u32 = value.toInt()"
        generated.bridge shouldContain "handle.writeField(\"u32\")"
        generated.bridge shouldContain "return handle.boolean != 0"
        generated.bridge shouldContain "handle.boolean = if (value) 1 else 0"
    }

    "representative general union common raw and Android bridge sources compile together" {
        compileGeneratedAndroid(generateAndroidSources(ABI_HEADER))
    }

    "copying a generated tagged display handle inline preserves its active Xlib payload" {
        compileGeneratedAndroid(generateAndroidSources(NATIVE_DISPLAY_HEADER), NATIVE_DISPLAY_COPY_PROBE)
            ?.toList() shouldBe listOf(0x1234L, 7L)
    }

    "copying a generated general union inline preserves its active payload" {
        compileGeneratedAndroid(generateAndroidSources(ABI_HEADER), GENERAL_UNION_COPY_PROBE)
            ?.toList() shouldBe listOf(0x12345678L)
    }

    "compiled generated JNA structs match libclang size offsets field types and order" {
        val workspace = Files.createTempDirectory("kextract-kmp-android-abi")
        val input = workspace.resolve("wgpu_abi.h")
        try {
            input.toFile().writeText(ABI_HEADER)
            val parsed = KextractTool.parse(listOf(input.toString()))
            val generated = generateAndroidSources(ABI_HEADER)
            compileGeneratedJna(generated.jna).use { compiled ->
                assertMatchesClangLayout(
                    parsed,
                    "WGPUBlendState",
                    compiled.structure("WGPUBlendState"),
                    listOf(
                        "color" to "sample.bindings.android.WGPUBlendComponent\$ByValue",
                        "alpha" to "sample.bindings.android.WGPUBlendComponent\$ByValue",
                    ),
                )

                assertMatchesClangLayout(
                    parsed,
                    "WGPUDeviceDescriptor",
                    compiled.structure("WGPUDeviceDescriptor"),
                    listOf(
                        "nextInChain" to "com.sun.jna.Pointer",
                        "label" to "sample.bindings.android.WGPUStringView\$ByValue",
                        "requiredFeatureCount" to "long",
                        "requiredFeatures" to "com.sun.jna.Pointer",
                        "requiredLimits" to "com.sun.jna.Pointer",
                        "defaultQueue" to "sample.bindings.android.WGPUQueueDescriptor\$ByValue",
                        "deviceLostCallbackInfo" to "sample.bindings.android.WGPUDeviceLostCallbackInfo\$ByValue",
                        "uncapturedErrorCallbackInfo" to
                            "sample.bindings.android.WGPUUncapturedErrorCallbackInfo\$ByValue",
                    ),
                )

                assertMatchesClangLayout(
                    parsed,
                    "WGPUScalar",
                    compiled.structure("WGPUScalar"),
                    listOf(
                        "u32" to "int",
                        "f64" to "double",
                        "boolean" to "int",
                    ),
                )

                assertMatchesClangLayout(
                    parsed,
                    "WGPUUnionContainer",
                    compiled.structure("WGPUUnionContainer"),
                    listOf(
                        "scalar" to "sample.bindings.android.WGPUScalar\$ByValue",
                        "scalarPointer" to "com.sun.jna.Pointer",
                    ),
                )
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    "compiled generated callback infos directly match libclang layouts" {
        val workspace = Files.createTempDirectory("kextract-kmp-android-callback-abi")
        val input = workspace.resolve("wgpu_callback_abi.h")
        try {
            input.toFile().writeText(ABI_HEADER)
            val parsed = KextractTool.parse(listOf(input.toString()))
            val generated = generateAndroidSources(ABI_HEADER)
            compileGeneratedJna(generated.jna).use { compiled ->
                assertMatchesClangLayout(
                    parsed,
                    "WGPUDeviceLostCallbackInfo",
                    compiled.structure("WGPUDeviceLostCallbackInfo"),
                    listOf(
                        "nextInChain" to "com.sun.jna.Pointer",
                        "mode" to "int",
                        "callback" to "com.sun.jna.Pointer",
                        "userdata1" to "com.sun.jna.Pointer",
                        "userdata2" to "com.sun.jna.Pointer",
                    ),
                )
                assertMatchesClangLayout(
                    parsed,
                    "WGPUUncapturedErrorCallbackInfo",
                    compiled.structure("WGPUUncapturedErrorCallbackInfo"),
                    listOf(
                        "nextInChain" to "com.sun.jna.Pointer",
                        "callback" to "com.sun.jna.Pointer",
                        "userdata1" to "com.sun.jna.Pointer",
                        "userdata2" to "com.sun.jna.Pointer",
                    ),
                )
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    "compiled generated native display tagged union directly matches libclang layout" {
        val workspace = Files.createTempDirectory("kextract-kmp-android-display-abi")
        val input = workspace.resolve("wgpu_display_abi.h")
        try {
            input.toFile().writeText(NATIVE_DISPLAY_HEADER)
            val parsed = KextractTool.parse(listOf(input.toString()))
            val generated = generateAndroidSources(NATIVE_DISPLAY_HEADER)
            compileGeneratedJna(generated.jna).use { compiled ->
                val xlib = compiled.structure("WGPUXlibDisplayHandle")
                assertMatchesClangLayout(
                    parsed,
                    "WGPUXlibDisplayHandle",
                    xlib,
                    listOf("display" to "com.sun.jna.Pointer", "screen" to "int"),
                )
                val xcb = compiled.structure("WGPUXcbDisplayHandle")
                assertMatchesClangLayout(
                    parsed,
                    "WGPUXcbDisplayHandle",
                    xcb,
                    listOf("connection" to "com.sun.jna.Pointer", "screen" to "int"),
                )
                val wayland = compiled.structure("WGPUWaylandDisplayHandle")
                assertMatchesClangLayout(
                    parsed,
                    "WGPUWaylandDisplayHandle",
                    wayland,
                    listOf("display" to "com.sun.jna.Pointer"),
                )

                assertJnaUnionLayout(
                    compiled.nestedStructure("WGPUNativeDisplayHandle", "Data"),
                    maxOf(xlib.size(), xcb.size(), wayland.size()),
                    listOf(
                        "xlib" to "sample.bindings.android.WGPUXlibDisplayHandle\$ByValue",
                        "xcb" to "sample.bindings.android.WGPUXcbDisplayHandle\$ByValue",
                        "wayland" to "sample.bindings.android.WGPUWaylandDisplayHandle\$ByValue",
                    ),
                )
                assertMatchesClangLayout(
                    parsed,
                    "WGPUNativeDisplayHandle",
                    compiled.structure("WGPUNativeDisplayHandle"),
                    listOf(
                        "type" to "int",
                        "data" to "sample.bindings.android.WGPUNativeDisplayHandle\$Data",
                    ),
                )
                assertMatchesClangLayout(
                    parsed,
                    "WGPUInstanceExtras",
                    compiled.structure("WGPUInstanceExtras"),
                    listOf(
                        "displayHandle" to "sample.bindings.android.WGPUNativeDisplayHandle\$ByValue",
                    ),
                )
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
})
