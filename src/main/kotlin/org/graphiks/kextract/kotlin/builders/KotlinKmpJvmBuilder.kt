@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.callbacks.KotlinCallbackBindingEmitter
import org.graphiks.kextract.kotlin.callbacks.KotlinCallbackJvmEmitter
import org.graphiks.kextract.kotlin.callbacks.KotlinCallbackModel
import org.graphiks.kextract.kotlin.callbacks.KotlinDirectFunctionBindingModel
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.pipeline.LayoutUtils
import org.graphiks.kextract.kotlin.utils.TypeMapper
import org.graphiks.kextract.pipeline.isStructOrUnion
import org.graphiks.kextract.pipeline.isEnum

class KotlinKmpJvmBuilder(
    private val targetPackage: String,
    private val className: String,
    private val callbackModels: List<KotlinCallbackModel>,
    private val directBindingModels: List<KotlinDirectFunctionBindingModel>,
) : Declaration.Visitor<Unit> {

    private val builder = SourceBuilder()
    private val files = mutableListOf<KotlinSourceFile>()
    private val generatedNames = mutableSetOf<String>()
    private val generatedStructNames = mutableSetOf<String>()
    private val callbackTypeNames = callbackModels.mapTo(mutableSetOf(), KotlinCallbackModel::typeName)
    private val opaqueHandleAliases = mutableMapOf<String, String>()
    private val typeMapper = KmpTypeMapper(opaqueHandleAliases, generatedStructNames)

    init {
        if (targetPackage.isNotEmpty()) {
            builder.appendLine("package $targetPackage")
            builder.appendLine()
        }

        builder.appendLine("import io.ygdrasil.kffi.NativeAddress")
        builder.appendLine("import io.ygdrasil.kffi.CallbackExceptionHandler")
        builder.appendLine("import io.ygdrasil.kffi.CallbackPolicy")
        builder.appendLine("import io.ygdrasil.kffi.CallbackRegistration")
        builder.appendLine("import io.ygdrasil.kffi.CallbackRuntime")
        builder.appendLine("import io.ygdrasil.kffi.CallbackRuntimeApi")
        builder.appendLine("import io.ygdrasil.kffi.PreparedCallbackRegistration")
        builder.appendLine("import io.ygdrasil.kffi.UnsafeCallbackRearmApi")
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
        if (Skip.isPresent(decl)) return
        when (decl.kind()) {
            Declaration.Scoped.Kind.STRUCT,
            Declaration.Scoped.Kind.UNION -> {
                val structName = decl.name()
                if (structName.isEmpty() || structName.contains("unnamed")) return
                if (structName.endsWith("Impl") && decl.members().isEmpty()) return
                if (!generatedNames.add(structName)) return
                generatedStructNames.add(structName)
                if (structName == "WGPUNativeDisplayHandle") {
                    emitNativeDisplayHandle(decl)
                    return
                }

                builder.appendLine("actual interface $structName : CStructure {")
                builder.indent()

                val fields = decl.members().filterIsInstance<Declaration.Variable>().filterNot(Skip::isPresent)

                // Visit members as actual properties
                fields.forEach { field ->
                    val fieldName = field.name()
                    val fieldType = typeMapper.mapType(field.type())
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
                emitGroupLayout(decl, fields)
                builder.appendLine()

                // VarHandles for value fields
                fields.forEach { field ->
                    val fieldName = field.name()
                    val isArray = isArrayType(field.type())
                    val isStruct = typeMapper.isInlineStructOrUnion(field.type())
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
                    val fieldType = typeMapper.mapType(field.type())
                    val isArray = isArrayType(field.type())
                    val isStruct = typeMapper.isInlineStructOrUnion(field.type())

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
                        val canonical = typeMapper.canonicalKmpType(field.type())
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
                KotlinCallbackJvmEmitter(typeMapper::mapFunctionType).emit(builder, callbackModels)
                KotlinCallbackBindingEmitter(typeMapper::mapFunctionType).emitJvm(
                    builder,
                    directBindingModels,
                    ::toRawJvmArgument,
                )
            }
            else -> {}
        }

        if (decl.kind() == Declaration.Scoped.Kind.TOPLEVEL) {
            files.add(
                KotlinSourceFile(
                    targetPackage,
                    className + "Jvm",
                    builder.toString(),
                    sourceRoot = "jvmMain/kotlin",
                ),
            )
        }
    }

    private fun isArrayType(type: Type): Boolean = when {
        type is Type.Array -> true
        type is Type.Delegated -> isArrayType(type.type())
        else -> false
    }

    private fun emitNativeDisplayHandle(decl: Declaration.Scoped) {
        val unionField = inlineUnionField(decl)
        val fields = decl.members()
            .filterIsInstance<Declaration.Variable>()
            .filterNot { it == unionField }
        val union = unionField?.type()?.let(typeMapper::declaredUnion)
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
            builder.appendLine("actual var ${field.name()}: ${typeMapper.mapType(field.type())}")
        }
        unionFields.forEach { field ->
            val fieldType = typeMapper.mapType(field.type())
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
            val fieldType = typeMapper.mapType(field.type())
            builder.appendLine("override var ${field.name()}: $fieldType")
            builder.indent()
            builder.appendLine("get() = (${field.name()}_VH.get(handler.handler, 0L) as Int).toUInt() as $fieldType")
            builder.appendLine("set(value) = ${field.name()}_VH.set(handler.handler, 0L, value.toInt())")
            builder.unindent()
        }
        unionFields.forEach { field ->
            val fieldName = field.name()
            val fieldType = typeMapper.mapType(field.type())
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
        if (Skip.isPresent(decl)) return
        emitFunction(decl)
    }
    override fun visitVariable(decl: Declaration.Variable) {}
    override fun visitTypedef(decl: Declaration.Typedef) {
        if (Skip.isPresent(decl)) return
        val name = decl.name()
        if (name.isEmpty()) return
        if (name in callbackTypeNames || typeMapper.callbackFunction(decl.type()) != null) return
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

    private fun emitGroupLayout(
        decl: Declaration.Scoped,
        fields: List<Declaration.Variable>,
    ) {
        when (decl.kind()) {
            Declaration.Scoped.Kind.STRUCT -> emitStructLayout(decl, fields)
            Declaration.Scoped.Kind.UNION -> emitUnionLayout(decl, fields)
            else -> error("Expected struct or union, found ${decl.kind()}")
        }
    }

    private fun emitStructLayout(
        decl: Declaration.Scoped,
        fields: List<Declaration.Variable>,
    ) {
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
        builder.appendLine(").withName(\"${decl.name()}\")")
    }

    private fun emitUnionLayout(
        decl: Declaration.Scoped,
        fields: List<Declaration.Variable>,
    ) {
        builder.appendLine("val layout: GroupLayout = MemoryLayout.unionLayout(")
        builder.indent()
        fields.forEachIndexed { index, field ->
            val comma = if (index < fields.lastIndex) "," else ""
            builder.appendLine(
                "${LayoutUtils.layoutString(field.type())}.withName(\"${field.name()}\")$comma",
            )
        }
        builder.unindent()
        builder.appendLine(").withName(\"${decl.name()}\")")
    }

    private fun emitFunction(decl: Declaration.Function) {
        val name = decl.name()
        val returnType = typeMapper.mapFunctionType(decl.type().returnType())
        val params = decl.parameters().mapIndexed { index, param ->
            val paramName = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            "$paramName: ${typeMapper.mapFunctionType(param.type())}"
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
            rawType == "Int" && typeMapper.isEnumType(type) -> {
                builder.appendLine("return ($invoke as Int).toUInt() as $returnType")
            }
            rawType == "Long" && returnType == "ULong" -> {
                builder.appendLine("return ($invoke as Long).toULong()")
            }
            rawType == "MemorySegment" && returnsStructByValue(type) -> {
                builder.appendLine("return $returnType(NativeAddress($invoke as MemorySegment))")
            }
            else -> {
                builder.appendLine("return $invoke as $returnType")
            }
        }
    }

    private fun returnsStructByValue(type: Type): Boolean =
        rawJvmType(type) == "MemorySegment" &&
            !returnsPointer(type) &&
            typeMapper.mapFunctionType(type) in generatedStructNames

    private fun returnsPointer(type: Type): Boolean = when {
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> true
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> returnsPointer(type.type())
        else -> false
    }

    private fun toRawJvmArgument(name: String, type: Type): String {
        val kmpType = typeMapper.mapFunctionType(type)
        val rawType = rawJvmType(type)
        return when {
            rawType == "MemorySegment" && kmpType == "NativeAddress?" -> "$name?.handler ?: MemorySegment.NULL"
            rawType == "MemorySegment" && kmpType == "CString?" -> "$name?.handler?.handler ?: MemorySegment.NULL"
            rawType == "MemorySegment" && kmpType.startsWith("ArrayHolder") -> "$name?.handler?.handler ?: MemorySegment.NULL"
            rawType == "MemorySegment" && kmpType.endsWith("?") -> "$name?.handler?.handler ?: MemorySegment.NULL"
            rawType == "MemorySegment" -> "$name.handler.handler"
            rawType == "Int" && (kmpType == "UInt" || typeMapper.isEnumType(type)) -> "$name.toInt()"
            rawType == "Int" && kmpType == "Boolean" -> "if ($name) 1 else 0"
            rawType == "Long" && kmpType == "ULong" -> "$name.toLong()"
            rawType == "Short" && kmpType == "UShort" -> "$name.toShort()"
            rawType == "Byte" && kmpType == "UByte" -> "$name.toByte()"
            else -> name
        }
    }

    private fun rawJvmType(type: Type): String = when {
        type is Type.Primitive -> typeMapper.mapPrimitive(type.kind())
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

    private fun inlineUnionField(decl: Declaration.Scoped): Declaration.Variable? =
        decl.members()
            .filterIsInstance<Declaration.Variable>()
            .firstOrNull { typeMapper.declaredUnion(it.type()) != null }

}
