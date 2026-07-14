package org.graphiks.kextract.kotlin

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import org.graphiks.kextract.callbacks.ValidatedCallbackBindings
import org.graphiks.kextract.kotlin.utils.KotlinIdentifierAllocator
import java.util.IdentityHashMap

internal enum class KotlinKmpSourceSet {
    COMMON,
    JVM,
    NATIVE,
    ANDROID,
}

private fun common() = setOf(KotlinKmpSourceSet.COMMON)
private fun jvm() = setOf(KotlinKmpSourceSet.JVM)
private fun native() = setOf(KotlinKmpSourceSet.NATIVE)
private fun android() = setOf(KotlinKmpSourceSet.ANDROID)
private fun commonJvmNative() = setOf(
    KotlinKmpSourceSet.COMMON,
    KotlinKmpSourceSet.JVM,
    KotlinKmpSourceSet.NATIVE,
)
private fun allSourceSets() = KotlinKmpSourceSet.entries.toSet()

internal enum class KotlinKmpRuntimeSymbol(
    val qualifiedName: String,
    val sourceSets: Set<KotlinKmpSourceSet>,
    val preferredName: String = qualifiedName.substringAfterLast('.'),
) {
    NATIVE_ADDRESS("io.ygdrasil.kffi.NativeAddress", allSourceSets()),
    CALLBACK("io.ygdrasil.kffi.Callback", common()),
    CALLBACK_EXCEPTION_HANDLER("io.ygdrasil.kffi.CallbackExceptionHandler", allSourceSets()),
    CALLBACK_POLICY("io.ygdrasil.kffi.CallbackPolicy", allSourceSets()),
    CALLBACK_REGISTRATION("io.ygdrasil.kffi.CallbackRegistration", allSourceSets()),
    CALLBACK_RUNTIME("io.ygdrasil.kffi.CallbackRuntime", commonJvmNative()),
    CALLBACK_RUNTIME_API("io.ygdrasil.kffi.CallbackRuntimeApi", allSourceSets()),
    CALLBACK_TYPE("io.ygdrasil.kffi.CallbackType", common()),
    PREPARED_CALLBACK_REGISTRATION("io.ygdrasil.kffi.PreparedCallbackRegistration", allSourceSets()),
    UNSAFE_CALLBACK_REARM_API("io.ygdrasil.kffi.UnsafeCallbackRearmApi", allSourceSets()),
    C_STRING("io.ygdrasil.kffi.CString", allSourceSets()),
    ARRAY_HOLDER("io.ygdrasil.kffi.ArrayHolder", allSourceSets()),
    MEMORY_ALLOCATOR("io.ygdrasil.kffi.MemoryAllocator", allSourceSets()),
    C_STRUCTURE("io.ygdrasil.kffi.CStructure", jvm()),
    FIND_OR_THROW("io.ygdrasil.kffi.findOrThrow", jvm()),
    TO_C_STRING("io.ygdrasil.kffi.toCString", native()),
    TO_ADDRESS("io.ygdrasil.kffi.toAddress", android()),

    ARENA("java.lang.foreign.Arena", jvm()),
    FUNCTION_DESCRIPTOR("java.lang.foreign.FunctionDescriptor", jvm()),
    GROUP_LAYOUT("java.lang.foreign.GroupLayout", jvm()),
    LINKER("java.lang.foreign.Linker", jvm()),
    MEMORY_LAYOUT("java.lang.foreign.MemoryLayout", jvm()),
    MEMORY_SEGMENT("java.lang.foreign.MemorySegment", jvm()),
    SEGMENT_ALLOCATOR("java.lang.foreign.SegmentAllocator", jvm()),
    VALUE_LAYOUT("java.lang.foreign.ValueLayout", jvm()),
    METHOD_HANDLE("java.lang.invoke.MethodHandle", jvm()),
    METHOD_HANDLES("java.lang.invoke.MethodHandles", jvm()),
    VAR_HANDLE("java.lang.invoke.VarHandle", jvm()),
    GROUP_ELEMENT("java.lang.foreign.MemoryLayout.PathElement.groupElement", jvm()),

    BYTE_VAR("kotlinx.cinterop.ByteVar", native()),
    C_OPAQUE_POINTER("kotlinx.cinterop.COpaquePointer", native()),
    C_OPAQUE_POINTER_VAR("kotlinx.cinterop.COpaquePointerVar", native()),
    C_VALUE("kotlinx.cinterop.CValue", native()),
    DOUBLE_VAR("kotlinx.cinterop.DoubleVar", native()),
    FLOAT_VAR("kotlinx.cinterop.FloatVar", native()),
    INT_VAR("kotlinx.cinterop.IntVar", native()),
    LONG_VAR("kotlinx.cinterop.LongVar", native()),
    SHORT_VAR("kotlinx.cinterop.ShortVar", native()),
    UBYTE_VAR("kotlinx.cinterop.UByteVar", native()),
    UINT_VAR("kotlinx.cinterop.UIntVar", native()),
    ULONG_VAR("kotlinx.cinterop.ULongVar", native()),
    USHORT_VAR("kotlinx.cinterop.UShortVar", native()),
    C_VALUE_FACTORY("kotlinx.cinterop.cValue", native()),
    POINTED("kotlinx.cinterop.pointed", native()),
    PTR("kotlinx.cinterop.ptr", native()),
    REINTERPRET("kotlinx.cinterop.reinterpret", native()),
    SIZE_OF("kotlinx.cinterop.sizeOf", native()),
    STATIC_C_FUNCTION("kotlinx.cinterop.staticCFunction", native()),
    USE_CONTENTS("kotlinx.cinterop.useContents", native()),

    JNA_POINTER("com.sun.jna.Pointer", android()),
    JNA_STRUCTURE("com.sun.jna.Structure", android()),
    JNA_UNION("com.sun.jna.Union", android()),

    OPT_IN("kotlin.OptIn", allSourceSets()),
    SUPPRESS("kotlin.Suppress", jvm()),
    UNSUPPORTED_OPERATION_EXCEPTION("kotlin.UnsupportedOperationException", android()),
    JVM_FIELD("kotlin.jvm.JvmField", android()),
    JVM_INLINE("kotlin.jvm.JvmInline", setOf(KotlinKmpSourceSet.JVM, KotlinKmpSourceSet.ANDROID)),
    JVM_STATIC("kotlin.jvm.JvmStatic", jvm()),
    ;
}

private data class KotlinKmpJnaHelperNames(
    val byReference: String,
    val byValue: String,
)

internal class KotlinKmpNamePlan private constructor(
    val topLevelNames: Set<String>,
    val renderedRuntimeNames: Set<String>,
    private val runtimeNames: Map<KotlinKmpRuntimeSymbol, String>,
    private val declarationNames: IdentityHashMap<Declaration, String>,
    private val memberNames: IdentityHashMap<Declaration.Variable, String>,
    private val jnaHelperNames: IdentityHashMap<Declaration.Scoped, KotlinKmpJnaHelperNames>,
    private val jnaHelperNamesByRecordName: Map<String, KotlinKmpJnaHelperNames>,
) {
    fun runtime(symbol: KotlinKmpRuntimeSymbol): String = runtimeNames.getValue(symbol)

    fun importLine(symbol: KotlinKmpRuntimeSymbol): String {
        val rendered = runtime(symbol)
        return if (rendered == symbol.preferredName) {
            "import ${symbol.qualifiedName}"
        } else {
            "import ${symbol.qualifiedName} as $rendered"
        }
    }

    fun declaration(declaration: Declaration): String = declarationNames.getValue(declaration)

    fun member(field: Declaration.Variable): String = memberNames.getValue(field)

    fun jnaByReference(record: Declaration.Scoped): String = jnaHelperNames.getValue(record).byReference

    fun jnaByValue(record: Declaration.Scoped): String = jnaHelperNames.getValue(record).byValue

    fun jnaByReference(recordName: String): String = jnaHelperNamesByRecordName.getValue(recordName).byReference

    fun jnaByValue(recordName: String): String = jnaHelperNamesByRecordName.getValue(recordName).byValue

    companion object {
        private val RECORD_RESERVED_MEMBERS = setOf(
            "handler",
            "Companion",
            "ByReference",
            "ByValue",
            "layout",
            "allocate",
            "allocateArray",
            "invoke",
        )

        fun create(
            scoped: Declaration.Scoped,
            callbackBindings: ValidatedCallbackBindings,
        ): KotlinKmpNamePlan {
            val cTopLevelNames = KotlinKmpExternalNameCollector.collect(scoped, callbackBindings)
            val runtimeAllocator = KotlinIdentifierAllocator(cTopLevelNames)
            val runtimeNames = KotlinKmpRuntimeSymbol.entries.associateWith { symbol ->
                if (symbol.preferredName !in cTopLevelNames) {
                    runtimeAllocator.allocate(symbol.preferredName, "KffiRuntime")
                } else {
                    runtimeAllocator.allocate("Kffi${symbol.preferredName}", "KffiRuntime")
                }
            }
            val declarationNames = IdentityHashMap<Declaration, String>()
            val memberNames = IdentityHashMap<Declaration.Variable, String>()
            val jnaHelperNames = IdentityHashMap<Declaration.Scoped, KotlinKmpJnaHelperNames>()

            val collector = object {
                fun collectType(type: Type) {
                    when (type) {
                        is Type.Declared -> collect(type.tree())
                        is Type.Delegated -> collectType(type.type())
                        is Type.Array -> collectType(type.elementType())
                        is Type.Function -> {
                            collectType(type.returnType())
                            type.argumentTypes().forEach(::collectType)
                        }
                    }
                }

                fun collect(declaration: Declaration) {
                    if (declarationNames.containsKey(declaration)) return
                    declarationNames[declaration] = declaration.name()
                    when (declaration) {
                        is Declaration.Function -> {
                            collectType(declaration.type())
                            declaration.parameters().forEach(::collect)
                        }
                        is Declaration.Typedef -> collectType(declaration.type())
                        is Declaration.Variable -> collectType(declaration.type())
                        is Declaration.Scoped -> {
                            if (
                                !Skip.isPresent(declaration) &&
                                declaration.kind() in setOf(Declaration.Scoped.Kind.STRUCT, Declaration.Scoped.Kind.UNION)
                            ) {
                                val fields = declaration.members()
                                    .filterIsInstance<Declaration.Variable>()
                                    .filterNot(Skip::isPresent)
                                val memberAllocator = KotlinIdentifierAllocator(RECORD_RESERVED_MEMBERS)
                                fields.forEach { field ->
                                    memberNames[field] = memberAllocator.allocate(field.name(), "field")
                                }
                                val jnaHelperAllocator = KotlinIdentifierAllocator(fields.map(Declaration.Variable::name))
                                jnaHelperNames[declaration] = KotlinKmpJnaHelperNames(
                                    byReference = jnaHelperAllocator.allocate("ByReference", "JnaByReference"),
                                    byValue = jnaHelperAllocator.allocate("ByValue", "JnaByValue"),
                                )
                            }
                            declaration.members().forEach(::collect)
                        }
                    }
                }
            }
            collector.collect(scoped)
            val jnaHelperNamesByRecordName = jnaHelperNames.entries.associate { (record, names) ->
                declarationNames.getValue(record) to names
            }

            return KotlinKmpNamePlan(
                topLevelNames = cTopLevelNames,
                renderedRuntimeNames = runtimeNames.values.toSet(),
                runtimeNames = runtimeNames,
                declarationNames = declarationNames,
                memberNames = memberNames,
                jnaHelperNames = jnaHelperNames,
                jnaHelperNamesByRecordName = jnaHelperNamesByRecordName,
            )
        }
    }
}
