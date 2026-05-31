package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.pipeline.LayoutUtils
import org.graphiks.kextract.kotlin.utils.TypeMapper
import org.graphiks.kextract.pipeline.isStructOrUnion
import org.graphiks.kextract.pipeline.isEnum

class KotlinKmpJvmBuilder(
    private val targetPackage: String,
    private val className: String
) : Declaration.Visitor<Unit> {

    private val builder = SourceBuilder()
    private val files = mutableListOf<KotlinSourceFile>()
    private val generatedNames = mutableSetOf<String>()
    private val generatedStructNames = mutableSetOf<String>()
    private val opaqueHandleAliases = mutableMapOf<String, String>()

    init {
        if (targetPackage.isNotEmpty()) {
            builder.appendLine("package $targetPackage")
            builder.appendLine()
        }

        builder.appendLine("import io.ygdrasil.kffi.NativeAddress")
        builder.appendLine("import io.ygdrasil.kffi.CallbackHolder")
        builder.appendLine("import io.ygdrasil.kffi.CString")
        builder.appendLine("import io.ygdrasil.kffi.ArrayHolder")
        builder.appendLine("import io.ygdrasil.kffi.MemoryAllocator")
        builder.appendLine("import io.ygdrasil.kffi.CStructure")
        builder.appendLine("import io.ygdrasil.kffi.findOrThrow")
        builder.appendLine("import java.lang.foreign.*")
        builder.appendLine("import java.lang.invoke.MethodHandle")
        builder.appendLine("import java.lang.invoke.MethodHandles")
        builder.appendLine("import java.lang.invoke.VarHandle")
        builder.appendLine("import java.lang.foreign.MemoryLayout.PathElement.groupElement")
        builder.appendLine()
    }

    override fun visitScoped(decl: Declaration.Scoped) {
        when (decl.kind()) {
            Declaration.Scoped.Kind.STRUCT,
            Declaration.Scoped.Kind.UNION -> {
                val structName = decl.name()
                if (structName.isEmpty() || structName.contains("unnamed") || (!structName.startsWith("WGPU") && !structName.startsWith("wgpu"))) return
                if (structName.endsWith("Impl") && decl.members().isEmpty()) return
                if (!generatedNames.add(structName)) return
                generatedStructNames.add(structName)
                if (structName == "WGPUNativeDisplayHandle") {
                    emitNativeDisplayHandle(decl)
                    return
                }

                builder.appendLine("actual interface $structName : CStructure {")
                builder.indent()

                val fields = decl.members().filterIsInstance<Declaration.Variable>()

                // Visit members as actual properties
                fields.forEach { field ->
                    val fieldName = field.name()
                    val fieldType = mapKmpType(field.type())
                    if (fieldType == "CString") {
                        builder.appendLine("actual var $fieldName: CString?")
                    } else if (fieldType.startsWith("ArrayHolder")) {
                        builder.appendLine("actual var $fieldName: $fieldType?")
                    } else if (fieldType.endsWith("?") || fieldType == "NativeAddress") {
                        builder.appendLine("actual var $fieldName: $fieldType")
                    } else {
                        builder.appendLine("actual var $fieldName: $fieldType")
                    }
                }

                builder.appendLine("actual override val handler: NativeAddress")

                // Companion object
                builder.appendLine("actual companion object {")
                builder.indent()

                // Define layout
                builder.appendLine("val layout: GroupLayout = MemoryLayout.structLayout(")
                builder.indent()
                var currentOffsetBits = 0L
                fields.forEachIndexed { i, field ->
                    val offsetBits = org.graphiks.kextract.DeclarationImpl.ClangOffsetOf.get(field)
                    if (offsetBits != null && offsetBits > currentOffsetBits) {
                        val paddingBytes = (offsetBits - currentOffsetBits) / 8
                        if (paddingBytes > 0) {
                            builder.appendLine("MemoryLayout.paddingLayout($paddingBytes),")
                        }
                    }
                    val layout = LayoutUtils.layoutString(field.type())
                    val sizeBits = org.graphiks.kextract.DeclarationImpl.ClangSizeOf.get(field) ?: 0L
                    currentOffsetBits = (offsetBits ?: currentOffsetBits) + sizeBits

                    val hasNext = i < fields.count() - 1
                    val structSizeBits = org.graphiks.kextract.DeclarationImpl.ClangSizeOf.get(decl) ?: 0L
                    val needsEndPadding = !hasNext && structSizeBits > currentOffsetBits
                    val comma = if (hasNext || needsEndPadding) "," else ""
                    builder.appendLine("${layout}.withName(\"${field.name()}\")${comma}")
                }
                val structSizeBits = org.graphiks.kextract.DeclarationImpl.ClangSizeOf.get(decl) ?: 0L
                if (structSizeBits > currentOffsetBits) {
                    val paddingBytes = (structSizeBits - currentOffsetBits) / 8
                    if (paddingBytes > 0) {
                        builder.appendLine("MemoryLayout.paddingLayout($paddingBytes)")
                    }
                }
                builder.unindent()
                builder.appendLine(").withName(\"$structName\")")
                builder.appendLine()

                // VarHandles for value fields
                fields.forEach { field ->
                    val fieldName = field.name()
                    val isArray = isArrayType(field.type())
                    val isStruct = isInlineStructOrUnion(field.type())
                    if (!isArray && !isStruct) {
                        builder.appendLine("val ${fieldName}_VH: VarHandle = layout.varHandle(groupElement(\"$fieldName\"))")
                    }
                }
                builder.appendLine()

                builder.appendLine("actual operator fun invoke(address: NativeAddress): $structName = ByReference(address)")
                builder.appendLine("actual fun allocate(allocator: MemoryAllocator): $structName = ByReference(allocator.allocate(layout.byteSize()))")
                builder.appendLine("actual fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, $structName) -> Unit): ArrayHolder<$structName> {")
                builder.indent()
                builder.appendLine("val byteSize = layout.byteSize()")
                builder.appendLine("val segment = allocator.allocate(byteSize * size.toLong())")
                builder.appendLine("for (i in 0 until size.toInt()) {")
                builder.indent()
                builder.appendLine("val slice = segment.handler.asSlice(i.toLong() * byteSize, byteSize).let(::NativeAddress)")
                builder.appendLine("provider(i.toUInt(), ByReference(slice))")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine("return ArrayHolder(segment)")
                builder.unindent()
                builder.appendLine("}")
                builder.unindent()
                builder.appendLine("}") // End companion object

                // @JvmInline value class ByReference implementation
                builder.appendLine()
                builder.appendLine("@JvmInline")
                builder.appendLine("value class ByReference(override val handler: NativeAddress) : $structName {")
                builder.indent()

                fields.forEach { field ->
                    val fieldName = field.name()
                    val fieldType = mapKmpType(field.type())
                    val isArray = isArrayType(field.type())
                    val isStruct = isInlineStructOrUnion(field.type())

                    if (isArray) {
                        // Array field using asSlice
                        builder.appendLine("override var $fieldName: $fieldType?")
                        builder.indent()
                        builder.appendLine("get() = handler.handler.asSlice(Companion.layout.byteOffset(groupElement(\"$fieldName\")), Companion.layout.select(groupElement(\"$fieldName\")).byteSize()).let(::NativeAddress).let(::ArrayHolder)")
                        builder.appendLine("set(value) {")
                        builder.indent()
                        builder.appendLine("if (value != null) {")
                        builder.indent()
                        builder.appendLine("MemorySegment.copy(value.handler.handler, 0L, handler.handler, Companion.layout.byteOffset(groupElement(\"$fieldName\")), Companion.layout.select(groupElement(\"$fieldName\")).byteSize())")
                        builder.unindent()
                        builder.appendLine("}")
                        builder.unindent()
                        builder.appendLine("}")
                        builder.unindent()
                    } else if (isStruct) {
                        val nonOptType = fieldType.removeSuffix("?")
                        builder.appendLine("override var $fieldName: $fieldType")
                        builder.indent()
                        builder.appendLine("get() = $nonOptType(NativeAddress(handler.handler.asSlice(Companion.layout.byteOffset(groupElement(\"$fieldName\")), Companion.layout.select(groupElement(\"$fieldName\")).byteSize())))")
                        builder.appendLine("set(value) {")
                        builder.indent()
                        builder.appendLine("MemorySegment.copy(value.handler.handler, 0L, handler.handler, Companion.layout.byteOffset(groupElement(\"$fieldName\")), Companion.layout.select(groupElement(\"$fieldName\")).byteSize())")
                        builder.unindent()
                        builder.appendLine("}")
                        builder.unindent()
                    } else {
                        // VarHandle based get/set
                        val propType = if (fieldType == "CString") "CString?" else fieldType
                        builder.appendLine("override var $fieldName: $propType")
                        builder.indent()
                        val canonical = canonicalKmpType(field.type())
                        when {
                            fieldType == "CString" -> {
                                builder.appendLine("get() = (${fieldName}_VH.get(handler.handler, 0L) as? MemorySegment)?.let(::NativeAddress)?.let(::CString)")
                                builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value?.handler?.handler ?: MemorySegment.NULL)")
                            }
                            fieldType == "NativeAddress" -> {
                                builder.appendLine("get() = (${fieldName}_VH.get(handler.handler, 0L) as? MemorySegment)?.let(::NativeAddress) ?: NativeAddress(MemorySegment.NULL)")
                                builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value.handler)")
                            }
                            fieldType == "NativeAddress?" -> {
                                builder.appendLine("get() = (${fieldName}_VH.get(handler.handler, 0L) as? MemorySegment)?.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)")
                                builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value?.handler ?: MemorySegment.NULL)")
                            }
                            canonical == "Boolean" -> {
                                builder.appendLine("get() = ${fieldName}_VH.get(handler.handler, 0L) as Boolean")
                                builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value)")
                            }
                            canonical == "UInt" -> {
                                builder.appendLine("get() = (${fieldName}_VH.get(handler.handler, 0L) as Int).toUInt() as $fieldType")
                                builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value.toInt())")
                            }
                            canonical == "ULong" -> {
                                builder.appendLine("get() = (${fieldName}_VH.get(handler.handler, 0L) as Long).toULong() as $fieldType")
                                builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value.toLong())")
                            }
                            canonical == "UShort" -> {
                                builder.appendLine("get() = (${fieldName}_VH.get(handler.handler, 0L) as Short).toUShort() as $fieldType")
                                builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value.toShort())")
                            }
                            canonical == "UByte" -> {
                                builder.appendLine("get() = (${fieldName}_VH.get(handler.handler, 0L) as Byte).toUByte() as $fieldType")
                                builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value.toByte())")
                            }
                            else -> {
                                if (fieldType.endsWith("?")) {
                                    val nonOptType = fieldType.removeSuffix("?")
                                    builder.appendLine("get() = (${fieldName}_VH.get(handler.handler, 0L) as? MemorySegment)?.let(::NativeAddress)?.let { $nonOptType(it) }")
                                    builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value?.handler?.handler ?: MemorySegment.NULL)")
                                } else {
                                    builder.appendLine("get() = ${fieldName}_VH.get(handler.handler, 0L) as $fieldType")
                                    builder.appendLine("set(value) = ${fieldName}_VH.set(handler.handler, 0L, value)")
                                }
                            }
                        }
                        builder.unindent()
                    }
                }

                builder.unindent()
                builder.appendLine("}") // End value class ByReference

                builder.unindent()
                builder.appendLine("}") // End actual interface
                builder.appendLine()
            }
            Declaration.Scoped.Kind.TOPLEVEL -> {
                for (member in decl.members()) {
                    member.accept(this)
                }
            }
            else -> {}
        }

        if (decl.kind() == Declaration.Scoped.Kind.TOPLEVEL) {
            files.add(KotlinSourceFile(targetPackage, className + "Jvm", builder.toString()))
        }
    }

    private fun isArrayType(type: Type): Boolean = when {
        type is Type.Array -> true
        type is Type.Delegated -> isArrayType(type.type())
        else -> false
    }

    private fun isInlineStructOrUnion(type: Type): Boolean {
        val fieldType = mapKmpType(type)
        return canonicalKmpType(type) == "Other" &&
               fieldType != "NativeAddress" &&
               fieldType != "CString" &&
               !fieldType.endsWith("?")
    }

    private fun emitNativeDisplayHandle(decl: Declaration.Scoped) {
        val unionField = inlineUnionField(decl)
        val fields = decl.members()
            .filterIsInstance<Declaration.Variable>()
            .filterNot { it == unionField }
        val union = unionField?.type()?.declaredUnion()
            ?: decl.members()
                .filterIsInstance<Declaration.Scoped>()
                .firstOrNull { it.kind() == Declaration.Scoped.Kind.UNION }
            ?: return
        val unionFields = union.members().filterIsInstance<Declaration.Variable>()
        val unionOffsetBits = unionField
            ?.let { org.graphiks.kextract.DeclarationImpl.ClangOffsetOf.get(it) }
            ?: org.graphiks.kextract.DeclarationImpl.AnonymousStruct.getOrThrow(union).offset
            ?: 0L
        val unionSizeBits = unionField
            ?.let { org.graphiks.kextract.DeclarationImpl.ClangSizeOf.get(it) }
            ?: org.graphiks.kextract.DeclarationImpl.ClangSizeOf.get(union)
            ?: 0L
        val structSizeBits = org.graphiks.kextract.DeclarationImpl.ClangSizeOf.get(decl) ?: 0L

        builder.appendLine("actual interface WGPUNativeDisplayHandle : CStructure {")
        builder.indent()

        fields.forEach { field ->
            builder.appendLine("actual var ${field.name()}: ${mapKmpType(field.type())}")
        }
        unionFields.forEach { field ->
            val fieldType = mapKmpType(field.type())
            val setter = field.name().replaceFirstChar { it.titlecase() }
            builder.appendLine("actual val ${field.name()}: $fieldType?")
            builder.appendLine("actual fun set$setter(value: $fieldType)")
        }
        builder.appendLine("actual override val handler: NativeAddress")

        builder.appendLine("actual companion object {")
        builder.indent()
        builder.appendLine("val layout: GroupLayout = MemoryLayout.structLayout(")
        builder.indent()
        var currentOffsetBits = 0L
        fields.forEach { field ->
            val offsetBits = org.graphiks.kextract.DeclarationImpl.ClangOffsetOf.get(field) ?: currentOffsetBits
            if (offsetBits > currentOffsetBits) {
                val paddingBytes = (offsetBits - currentOffsetBits) / 8
                if (paddingBytes > 0) builder.appendLine("MemoryLayout.paddingLayout($paddingBytes),")
            }
            builder.appendLine("${LayoutUtils.layoutString(field.type())}.withName(\"${field.name()}\"),")
            currentOffsetBits = offsetBits + (org.graphiks.kextract.DeclarationImpl.ClangSizeOf.get(field) ?: 0L)
        }
        if (unionOffsetBits > currentOffsetBits) {
            val paddingBytes = (unionOffsetBits - currentOffsetBits) / 8
            if (paddingBytes > 0) builder.appendLine("MemoryLayout.paddingLayout($paddingBytes),")
        }
        builder.appendLine("MemoryLayout.sequenceLayout(${unionSizeBits / 8L}, ValueLayout.JAVA_BYTE).withName(\"value\")${if (structSizeBits > unionOffsetBits + unionSizeBits) "," else ""}")
        currentOffsetBits = unionOffsetBits + unionSizeBits
        if (structSizeBits > currentOffsetBits) {
            val paddingBytes = (structSizeBits - currentOffsetBits) / 8
            if (paddingBytes > 0) builder.appendLine("MemoryLayout.paddingLayout($paddingBytes)")
        }
        builder.unindent()
        builder.appendLine(").withName(\"WGPUNativeDisplayHandle\")")
        builder.appendLine()
        fields.forEach { field ->
            builder.appendLine("val ${field.name()}_VH: VarHandle = layout.varHandle(groupElement(\"${field.name()}\"))")
        }
        builder.appendLine("private val valueOffset: Long = layout.byteOffset(groupElement(\"value\"))")
        builder.appendLine()
        builder.appendLine("actual operator fun invoke(address: NativeAddress): WGPUNativeDisplayHandle = ByReference(address)")
        builder.appendLine("actual fun allocate(allocator: MemoryAllocator): WGPUNativeDisplayHandle = ByReference(allocator.allocate(layout.byteSize()))")
        builder.appendLine("actual fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, WGPUNativeDisplayHandle) -> Unit): ArrayHolder<WGPUNativeDisplayHandle> {")
        builder.indent()
        builder.appendLine("val byteSize = layout.byteSize()")
        builder.appendLine("val segment = allocator.allocate(byteSize * size.toLong())")
        builder.appendLine("for (i in 0 until size.toInt()) {")
        builder.indent()
        builder.appendLine("val slice = segment.handler.asSlice(i.toLong() * byteSize, byteSize).let(::NativeAddress)")
        builder.appendLine("provider(i.toUInt(), ByReference(slice))")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("return ArrayHolder(segment)")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")

        builder.appendLine()
        builder.appendLine("@JvmInline")
        builder.appendLine("value class ByReference(override val handler: NativeAddress) : WGPUNativeDisplayHandle {")
        builder.indent()
        fields.forEach { field ->
            val fieldType = mapKmpType(field.type())
            builder.appendLine("override var ${field.name()}: $fieldType")
            builder.indent()
            builder.appendLine("get() = (${field.name()}_VH.get(handler.handler, 0L) as Int).toUInt() as $fieldType")
            builder.appendLine("set(value) = ${field.name()}_VH.set(handler.handler, 0L, value.toInt())")
            builder.unindent()
        }
        unionFields.forEach { field ->
            val fieldName = field.name()
            val fieldType = mapKmpType(field.type())
            val setter = fieldName.replaceFirstChar { it.titlecase() }
            val discriminator = "WGPUNativeDisplayHandleType_$setter"
            builder.appendLine("override val $fieldName: $fieldType?")
            builder.indent()
            builder.appendLine("get() = if (type == $discriminator) $fieldType(NativeAddress(handler.handler.asSlice(valueOffset, $fieldType.layout.byteSize()))) else null")
            builder.unindent()
            builder.appendLine("override fun set$setter(value: $fieldType) {")
            builder.indent()
            builder.appendLine("type = $discriminator")
            builder.appendLine("MemorySegment.copy(value.handler.handler, 0L, handler.handler, valueOffset, $fieldType.layout.byteSize())")
            builder.unindent()
            builder.appendLine("}")
        }
        builder.unindent()
        builder.appendLine("}")

        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    override fun visitFunction(decl: Declaration.Function) {
        if (!decl.name().startsWith("wgpu")) return
        emitFunction(decl)
    }
    override fun visitVariable(decl: Declaration.Variable) {}
    override fun visitTypedef(decl: Declaration.Typedef) {
        val name = decl.name()
        if (name.isEmpty() || !name.startsWith("WGPU")) return
        if (name.endsWith("Callback")) decl.type().callbackFunction()?.let { function ->
            if (!generatedNames.add(name)) return
            emitCallbackActual(name, function)
            return
        }
        val inner = decl.type()
        if (inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER) {
            val pointee = inner.type()
            if (pointee is Type.Declared && pointee.tree().kind() == Declaration.Scoped.Kind.STRUCT) {
                val pointeeName = pointee.tree().name()
                if (pointeeName.isNotEmpty() && pointeeName.endsWith("Impl")) {
                    opaqueHandleAliases[pointeeName] = name
                    if (!generatedNames.add(name)) return
                    builder.appendLine("@kotlin.jvm.JvmInline")
                    builder.appendLine("actual value class $name actual constructor(actual val handler: NativeAddress)")
                    builder.appendLine()
                }
            }
        }
    }
    override fun visitConstant(decl: Declaration.Constant) {}
    override fun visitObjCClass(decl: Declaration.ObjCClass) {}
    override fun visitObjCProtocol(decl: Declaration.ObjCProtocol) {}
    override fun visitObjCCategory(decl: Declaration.ObjCCategory) {}

    fun getFiles(): List<KotlinSourceFile> = files

    private fun emitCallbackActual(name: String, function: Type.Function) {
        if (mapKmpFunctionType(function.returnType()) != "Unit") {
            builder.appendLine("// Callback $name is not generated: non-void callbacks are not supported yet.")
            builder.appendLine()
            return
        }

        builder.appendLine("actual class $name private constructor(")
        builder.indent()
        builder.appendLine("private var segment: MemorySegment,")
        builder.appendLine("private val arena: Arena,")
        builder.appendLine("private val callback: ${callbackLambdaType(function)}")
        builder.unindent()
        builder.appendLine(") : AutoCloseable {")
        builder.indent()
        builder.appendLine("actual val handler: NativeAddress")
        builder.indent()
        builder.appendLine("get() = NativeAddress(segment)")
        builder.unindent()
        builder.appendLine()

        val rawParams = function.parameterNames().orEmpty().mapIndexed { index, rawName ->
            val namePart = rawName.takeIf { it.isNotEmpty() } ?: "arg$index"
            "$namePart: ${rawJvmType(function.argumentTypes()[index])}"
        }
        builder.appendLine("fun invoke(${rawParams.joinToString(", ")}) {")
        builder.indent()
        val args = function.argumentTypes().mapIndexed { index, type ->
            val paramName = function.parameterNames().orEmpty().getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "arg$index"
            fromRawJvmCallbackArgument(paramName, type)
        }.joinToString(", ")
        builder.appendLine("callback($args)")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
        builder.appendLine("actual override fun close() {")
        builder.indent()
        builder.appendLine("arena.close()")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
        builder.appendLine("actual companion object {")
        builder.indent()
        builder.appendLine("private val DESC: FunctionDescriptor = ${LayoutUtils.functionDescriptorString(function)}")
        builder.appendLine("actual fun allocate(callback: ${callbackLambdaType(function)}): $name {")
        builder.indent()
        builder.appendLine("val arena = Arena.ofShared()")
        builder.appendLine("val holder = $name(MemorySegment.NULL, arena, callback)")
        builder.appendLine("val handle = MethodHandles.lookup()")
        builder.indent()
        builder.appendLine(".findVirtual($name::class.java, \"invoke\", DESC.toMethodType())")
        builder.appendLine(".bindTo(holder)")
        builder.unindent()
        builder.appendLine("holder.segment = Linker.nativeLinker().upcallStub(handle, DESC, arena)")
        builder.appendLine("return holder")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitFunction(decl: Declaration.Function) {
        val name = decl.name()
        val returnType = mapKmpFunctionType(decl.type().returnType())
        val params = decl.parameters().mapIndexed { index, param ->
            val paramName = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            "$paramName: ${mapKmpFunctionType(param.type())}"
        }
        val rawArgs = decl.parameters().mapIndexed { index, param ->
            val paramName = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            toRawJvmArgument(paramName, param.type())
        }
        val invokeArgs = if (returnsStructByValue(decl.type().returnType())) {
            listOf("(Arena.ofAuto() as SegmentAllocator)") + rawArgs
        } else {
            rawArgs
        }.joinToString(", ")
        val invoke = "${name}_HANDLE.invokeExact($invokeArgs)"
        builder.appendLine("private val ${name}_DESC: FunctionDescriptor = ${LayoutUtils.functionDescriptorString(decl.type())}")
        builder.appendLine("private val ${name}_ADDR: MemorySegment by lazy { findOrThrow(\"$name\") }")
        builder.appendLine("private val ${name}_HANDLE: MethodHandle by lazy { Linker.nativeLinker().downcallHandle(${name}_ADDR, ${name}_DESC) }")
        builder.appendLine("actual fun $name(${params.joinToString(", ")}): $returnType {")
        builder.indent()
        emitFunctionReturn(decl.type().returnType(), returnType, invoke)
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitFunctionReturn(type: Type, returnType: String, invoke: String) {
        if (returnType == "Unit") {
            builder.appendLine(invoke)
            builder.appendLine("return")
            return
        }
        val rawType = rawJvmType(type)
        when {
            returnType == "NativeAddress?" -> {
                builder.appendLine("return ($invoke as MemorySegment).takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)")
            }
            returnType == "CString?" -> {
                builder.appendLine("return ($invoke as MemorySegment).takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)?.let(::CString)")
            }
            returnType.endsWith("?") && rawType == "MemorySegment" -> {
                val nonOpt = returnType.removeSuffix("?")
                builder.appendLine("return ($invoke as MemorySegment).takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)?.let { $nonOpt(it) }")
            }
            rawType == "Int" && returnType == "Boolean" -> {
                builder.appendLine("return (($invoke as Int) != 0)")
            }
            rawType == "Int" && returnType == "UInt" -> {
                builder.appendLine("return ($invoke as Int).toUInt()")
            }
            rawType == "Int" && returnType.startsWith("WGPU") -> {
                builder.appendLine("return ($invoke as Int).toUInt() as $returnType")
            }
            rawType == "Long" && returnType == "ULong" -> {
                builder.appendLine("return ($invoke as Long).toULong()")
            }
            rawType == "Long" && returnType.startsWith("WGPU") -> {
                builder.appendLine("return ($invoke as Long).toULong() as $returnType")
            }
            rawType == "MemorySegment" && returnType.startsWith("WGPU") -> {
                builder.appendLine("return $returnType(NativeAddress($invoke as MemorySegment))")
            }
            else -> {
                builder.appendLine("return $invoke as $returnType")
            }
        }
    }

    private fun returnsStructByValue(type: Type): Boolean =
        rawJvmType(type) == "MemorySegment" &&
            mapKmpFunctionType(type).let { it.startsWith("WGPU") && !it.endsWith("?") }

    private fun toRawJvmArgument(name: String, type: Type): String {
        val kmpType = mapKmpFunctionType(type)
        val rawType = rawJvmType(type)
        return when {
            rawType == "MemorySegment" && kmpType == "NativeAddress?" -> "$name?.handler ?: MemorySegment.NULL"
            rawType == "MemorySegment" && kmpType == "CString?" -> "$name?.handler?.handler ?: MemorySegment.NULL"
            rawType == "MemorySegment" && kmpType.startsWith("ArrayHolder") -> "$name?.handler?.handler ?: MemorySegment.NULL"
            rawType == "MemorySegment" && kmpType.endsWith("?") -> "$name?.handler?.handler ?: MemorySegment.NULL"
            rawType == "MemorySegment" -> "$name.handler.handler"
            rawType == "Int" && (kmpType == "UInt" || kmpType.startsWith("WGPU")) -> "$name.toInt()"
            rawType == "Int" && kmpType == "Boolean" -> "if ($name) 1 else 0"
            rawType == "Long" && (kmpType == "ULong" || kmpType.startsWith("WGPU")) -> "$name.toLong()"
            rawType == "Short" && kmpType == "UShort" -> "$name.toShort()"
            rawType == "Byte" && kmpType == "UByte" -> "$name.toByte()"
            else -> name
        }
    }

    private fun fromRawJvmCallbackArgument(name: String, type: Type): String {
        val kmpType = mapKmpFunctionType(type)
        val rawType = rawJvmType(type)
        return when {
            rawType == "MemorySegment" && kmpType == "NativeAddress?" ->
                "$name.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)"
            rawType == "MemorySegment" && kmpType == "CString?" ->
                "$name.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)?.let(::CString)"
            rawType == "MemorySegment" && kmpType.endsWith("?") -> {
                val nonOpt = kmpType.removeSuffix("?")
                "$name.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)?.let { $nonOpt(it) }"
            }
            rawType == "MemorySegment" && kmpType != "NativeAddress" ->
                "$kmpType(NativeAddress($name))"
            rawType == "MemorySegment" ->
                "NativeAddress($name)"
            rawType == "Int" && kmpType == "Boolean" ->
                "($name != 0)"
            rawType == "Int" && (kmpType == "UInt" || kmpType.startsWith("WGPU")) ->
                "$name.toUInt() as $kmpType"
            rawType == "Long" && (kmpType == "ULong" || kmpType.startsWith("WGPU")) ->
                "$name.toULong() as $kmpType"
            rawType == "Short" && kmpType == "UShort" ->
                "$name.toUShort()"
            rawType == "Byte" && kmpType == "UByte" ->
                "$name.toUByte()"
            else -> name
        }
    }

    private fun rawJvmType(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED -> {
            val inner = type.type()
            if (inner is Type.Primitive) {
                when (inner.kind()) {
                    Type.Primitive.Kind.Char -> "Byte"
                    Type.Primitive.Kind.Short -> "Short"
                    Type.Primitive.Kind.Int -> "Int"
                    Type.Primitive.Kind.Long, Type.Primitive.Kind.LongLong -> "Long"
                    else -> "Int"
                }
            } else {
                "Int"
            }
        }
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> rawJvmType(type.type())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> "MemorySegment"
        type is Type.Declared && type.isEnum() -> "Int"
        type is Type.Declared && type.isStructOrUnion() -> "MemorySegment"
        type is Type.Array -> "MemorySegment"
        type is Type.Function -> "MemorySegment"
        else -> "MemorySegment"
    }

    private fun mapKmpFunctionType(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED -> mapKmpType(type)
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> {
            val pointee = type.type()
            when {
                pointee is Type.Primitive && pointee.kind() == Type.Primitive.Kind.Char -> "CString?"
                pointee is Type.Function -> "NativeAddress?"
                pointee is Type.Delegated && pointee.kind() == Type.Delegated.Kind.TYPEDEF && pointee.type() is Type.Function -> "NativeAddress?"
                pointee is Type.Delegated && pointee.isGeneratedReferenceTypedef() -> "${pointee.referenceTypeName()}?"
                pointee is Type.Declared && (pointee.tree().kind() == Declaration.Scoped.Kind.STRUCT || pointee.tree().kind() == Declaration.Scoped.Kind.UNION) -> {
                    val n = pointee.tree().name()
                    opaqueHandleAliases[n]?.let { "$it?" }
                        ?: n.takeIf { it.startsWith("WGPU") && it.endsWith("Impl") }?.removeSuffix("Impl")?.let { "$it?" }
                        ?: if (n.isNotEmpty() && !n.contains("unnamed")) "$n?" else "NativeAddress?"
                }
                else -> "NativeAddress?"
            }
        }
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> {
            val typedefName = type.name()
            val inner = type.type()
            when {
                type.callbackFunction() != null && typedefName != null && typedefName.startsWith("WGPU") && typedefName.endsWith("Callback") -> "$typedefName?"
                inner is Type.Function -> "NativeAddress?"
                inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER && inner.type() is Type.Function -> "NativeAddress?"
                inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER ->
                    if (typedefName != null && typedefName.startsWith("WGPU")) "$typedefName?" else "NativeAddress?"
                else -> {
                    val innerMapped = mapKmpType(inner)
                    if (innerMapped != "NativeAddress" && !innerMapped.contains("unnamed")) innerMapped else typedefName ?: "NativeAddress"
                }
            }
        }
        type is Type.Function -> "NativeAddress?"
        type is Type.Declared -> {
            val n = type.tree().name()
            if (n.isNotEmpty() && !n.contains("unnamed")) n else "NativeAddress"
        }
        type is Type.Array -> "ArrayHolder<${mapKmpFunctionType(type.elementType()).removeSuffix("?")}>?"
        else -> "NativeAddress"
    }

    private fun Type.isReferenceTypedef(): Boolean = when (this) {
        is Type.Delegated -> when (kind()) {
            Type.Delegated.Kind.TYPEDEF -> type().isReferenceTypedef()
            Type.Delegated.Kind.POINTER -> true
            else -> type().isReferenceTypedef()
        }
        is Type.Declared -> tree().kind() == Declaration.Scoped.Kind.STRUCT || tree().kind() == Declaration.Scoped.Kind.UNION
        else -> false
    }

    private fun Type.referenceTypeName(): String? = when (this) {
        is Type.Delegated -> (name() ?: type().referenceTypeName())?.toPublicHandleName()
        is Type.Declared -> tree().name().takeIf { it.isNotEmpty() && !it.contains("unnamed") }?.toPublicHandleName()
        else -> null
    }

    private fun String.toPublicHandleName(): String =
        if (startsWith("WGPU") && endsWith("Impl")) removeSuffix("Impl") else this

    private fun Type.isGeneratedReferenceTypedef(): Boolean {
        val name = referenceTypeName()
        return name != null && name.startsWith("WGPU") && (isReferenceTypedef() || name in generatedStructNames)
    }

    private fun Type.callbackFunction(): Type.Function? = when {
        this is Type.Delegated && kind() == Type.Delegated.Kind.TYPEDEF -> type().callbackFunction()
        this is Type.Delegated && kind() == Type.Delegated.Kind.POINTER -> type().callbackFunction()
        this is Type.Function -> this
        else -> null
    }

    private fun callbackLambdaType(function: Type.Function): String {
        val names = function.parameterNames().orEmpty()
        val params = function.argumentTypes().mapIndexed { index, type ->
            val name = names.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "arg$index"
            "$name: ${mapKmpFunctionType(type)}"
        }.joinToString(", ")
        return "($params) -> ${mapKmpFunctionType(function.returnType())}"
    }

    private fun inlineUnionField(decl: Declaration.Scoped): Declaration.Variable? =
        decl.members()
            .filterIsInstance<Declaration.Variable>()
            .firstOrNull { it.type().declaredUnion() != null }

    private fun Type.declaredUnion(): Declaration.Scoped? = when (this) {
        is Type.Declared -> tree().takeIf { it.kind() == Declaration.Scoped.Kind.UNION }
        is Type.Delegated -> type().declaredUnion()
        else -> null
    }

    private fun mapKmpType(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED -> {
            val inner = type.type()
            if (inner is Type.Primitive) {
                when (inner.kind()) {
                    Type.Primitive.Kind.Char -> "UByte"
                    Type.Primitive.Kind.Short -> "UShort"
                    Type.Primitive.Kind.Int -> "UInt"
                    Type.Primitive.Kind.Long, Type.Primitive.Kind.LongLong -> "ULong"
                    else -> "UInt"
                }
            } else {
                "UInt"
            }
        }
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> {
            val pointee = type.type()
            when {
                pointee is Type.Primitive && pointee.kind() == Type.Primitive.Kind.Char -> "CString"
                pointee is Type.Delegated && pointee.isGeneratedReferenceTypedef() -> "${pointee.referenceTypeName()}?"
                pointee is Type.Declared && (pointee.tree().kind() == Declaration.Scoped.Kind.STRUCT || pointee.tree().kind() == Declaration.Scoped.Kind.UNION) -> {
                    val name = pointee.tree().name()
                    opaqueHandleAliases[name]?.let { "$it?" }
                        ?: name.takeIf { it.startsWith("WGPU") && it.endsWith("Impl") }?.removeSuffix("Impl")?.let { "$it?" }
                        ?: if (name.isNotEmpty() && !name.contains("unnamed")) "$name?" else "NativeAddress?"
                }
                else -> "NativeAddress?"
            }
        }
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> {
            val inner = type.type()
            if (inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER) {
                val pointee = inner.type()
                if (pointee is Type.Declared && pointee.tree().kind() == Declaration.Scoped.Kind.STRUCT) {
                    val pointeeName = pointee.tree().name()
                    val typedefName = type.name()
                    if (pointeeName.isNotEmpty() && pointeeName.endsWith("Impl") && typedefName != null) {
                        "$typedefName?"
                    } else if (pointeeName.isNotEmpty() && pointeeName.startsWith("WGPU") && pointeeName.endsWith("Impl")) {
                        "${pointeeName.removeSuffix("Impl")}?"
                    } else if (pointeeName.isNotEmpty() && !pointeeName.contains("unnamed")) {
                        "$pointeeName?"
                    } else {
                        "NativeAddress?"
                    }
                } else {
                    "NativeAddress?"
                }
            } else {
                val innerMapped = mapKmpType(inner)
                if (innerMapped != "NativeAddress" && innerMapped != "NativeAddress?" && !innerMapped.contains("unnamed")) {
                    innerMapped
                } else {
                    val name = type.name()
                    if (name != null && !name.contains("unnamed")) name else "NativeAddress"
                }
            }
        }
        type is Type.Declared -> {
            val tree = type.tree()
            val name = tree.name()
            if (name.isNotEmpty() && !name.contains("unnamed")) name else "NativeAddress"
        }
        else -> "NativeAddress"
    }

    private fun mapPrimitive(kind: Type.Primitive.Kind): String = when (kind) {
        Type.Primitive.Kind.Bool -> "Boolean"
        Type.Primitive.Kind.Char -> "Byte"
        Type.Primitive.Kind.Short -> "Short"
        Type.Primitive.Kind.Int -> "Int"
        Type.Primitive.Kind.Long, Type.Primitive.Kind.LongLong -> "Long"
        Type.Primitive.Kind.Float -> "Float"
        Type.Primitive.Kind.Double -> "Double"
        Type.Primitive.Kind.Void -> "Unit"
        else -> "NativeAddress"
    }

    private fun canonicalType(type: Type): Type = when {
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> canonicalType(type.type())
        else -> type
    }

    private fun isEnumType(type: Type): Boolean = when {
        type.isEnum() -> true
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> isEnumType(type.type())
        else -> false
    }

    private fun canonicalKmpType(type: Type): String {
        val canonical = canonicalType(type)
        return when {
            canonical is Type.Primitive -> mapPrimitive(canonical.kind())
            isEnumType(canonical) -> "UInt"
            canonical is Type.Delegated && canonical.kind() == Type.Delegated.Kind.UNSIGNED -> {
                val inner = canonical.type()
                if (inner is Type.Primitive) {
                    when (inner.kind()) {
                        Type.Primitive.Kind.Char -> "UByte"
                        Type.Primitive.Kind.Short -> "UShort"
                        Type.Primitive.Kind.Int -> "UInt"
                        Type.Primitive.Kind.Long, Type.Primitive.Kind.LongLong -> "ULong"
                        else -> "UInt"
                    }
                } else {
                    "UInt"
                }
            }
            else -> "Other"
        }
    }
}
