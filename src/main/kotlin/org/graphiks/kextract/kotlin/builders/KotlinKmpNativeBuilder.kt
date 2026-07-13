@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.callbacks.KotlinCallbackModel
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.pipeline.isStructOrUnion
import org.graphiks.kextract.pipeline.isEnum

class KotlinKmpNativeBuilder(
    private val targetPackage: String,
    private val className: String,
    callbackModels: List<KotlinCallbackModel>,
) : Declaration.Visitor<Unit> {

    private val builder = SourceBuilder()
    private val files = mutableListOf<KotlinSourceFile>()
    private val generatedNames = mutableSetOf<String>()
    private val generatedStructNames = mutableSetOf<String>()
    private val callbackTypeNames = callbackModels.mapTo(mutableSetOf(), KotlinCallbackModel::typeName)
    private val opaqueHandleAliases = mutableMapOf<String, String>()
    private val typeMapper = KmpTypeMapper(opaqueHandleAliases, generatedStructNames)

    init {
        builder.appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
        builder.appendLine()
        if (targetPackage.isNotEmpty()) {
            builder.appendLine("package $targetPackage")
            builder.appendLine()
        }

        builder.appendLine("import io.ygdrasil.kffi.NativeAddress")
        builder.appendLine("import io.ygdrasil.kffi.CString")
        builder.appendLine("import io.ygdrasil.kffi.toCString")
        builder.appendLine("import io.ygdrasil.kffi.ArrayHolder")
        builder.appendLine("import io.ygdrasil.kffi.MemoryAllocator")
        builder.appendLine("import kotlinx.cinterop.*")
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
                    emitNativeDisplayHandle()
                    return
                }

                val fields = decl.members().filterIsInstance<Declaration.Variable>().filterNot(Skip::isPresent)

                builder.appendLine("actual interface $structName {")
                builder.indent()

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
                builder.appendLine("actual val handler: NativeAddress")

                // Companion object
                builder.appendLine("actual companion object {")
                builder.indent()
                builder.appendLine("actual operator fun invoke(address: NativeAddress): $structName = ByReference(address)")
                if (fields.isEmpty()) {
                    builder.appendLine("actual fun allocate(allocator: MemoryAllocator): $structName =")
                    builder.appendLine("    ByReference(allocator.allocate(8L))")
                } else {
                    builder.appendLine("actual fun allocate(allocator: MemoryAllocator): $structName =")
                    builder.appendLine("    ByReference(allocator.allocate(sizeOf<webgpu.native.$structName>().toLong()))")
                }
                builder.appendLine()
                builder.appendLine("actual fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, $structName) -> Unit): ArrayHolder<$structName> {")
                builder.indent()
                if (fields.isEmpty()) {
                    builder.appendLine("val byteSize = 8L")
                } else {
                    builder.appendLine("val byteSize = sizeOf<webgpu.native.$structName>().toLong()")
                }
                builder.appendLine("val segment = allocator.allocate(byteSize * size.toLong())")
                builder.appendLine("for (i in 0 until size.toInt()) {")
                builder.indent()
                builder.appendLine("val rawAddr = segment.rawValue + i.toLong() * byteSize")
                builder.appendLine("provider(i.toUInt(), ByReference(NativeAddress(rawAddr)))")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine("return ArrayHolder(segment)")
                builder.unindent()
                builder.appendLine("}")
                builder.unindent()
                builder.appendLine("}") // End companion
 
                if (fields.isNotEmpty()) {
                    builder.appendLine()
                    builder.appendLine("    value class ByValue(val handle: CValue<webgpu.native.$structName>) : $structName {")
                    builder.indent()
                    builder.appendLine("override val handler: NativeAddress")
                    builder.appendLine("    get() = error(\"should not be call on CValue\")")
                    builder.appendLine()
                    fields.forEach { field ->
                        val fieldName = field.name()
                        val fieldType = typeMapper.mapType(field.type())
                        if (fieldType == "CString") {
                            builder.appendLine("override var $fieldName: CString?")
                        } else if (fieldType.startsWith("ArrayHolder")) {
                            builder.appendLine("override var $fieldName: $fieldType?")
                        } else if (fieldType.endsWith("?") || fieldType == "NativeAddress") {
                            builder.appendLine("override var $fieldName: $fieldType")
                        } else {
                            builder.appendLine("override var $fieldName: $fieldType")
                        }
                        builder.indent()
                        when (fieldType) {
                            "CString" -> {
                                builder.appendLine("get() = handle.useContents { this.$fieldName?.let { CString(NativeAddress(it)) } }")
                                builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                            }
                            "NativeAddress" -> {
                                builder.appendLine("get() = handle.useContents { this.$fieldName?.let(::NativeAddress) ?: NativeAddress(0L) }")
                                builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                            }
                            "NativeAddress?" -> {
                                builder.appendLine("get() = handle.useContents { this.$fieldName?.let(::NativeAddress) }")
                                builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                            }
                            "Boolean" -> {
                                builder.appendLine("get() = handle.useContents { this.$fieldName }")
                                builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                            }
                            "Byte", "Short", "Int", "Long", "Float", "Double", "UByte", "UShort", "UInt", "ULong" -> {
                                builder.appendLine("get() = handle.useContents { this.$fieldName }")
                                builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                            }
                            else -> {
                                if (typeMapper.isInlineStructOrUnion(field.type())) {
                                    val isOpt = fieldType == "CString" || fieldType.startsWith("ArrayHolder") || fieldType.endsWith("?")
                                    if (isOpt) {
                                        val nonOpt = fieldType.removeSuffix("?")
                                        builder.appendLine("get() = handle.useContents { this.$fieldName?.let(::NativeAddress)?.let { $nonOpt.ByReference(it) } }")
                                        builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                                    } else {
                                        builder.appendLine("get() = handle.useContents { $fieldType.ByReference(NativeAddress(this.$fieldName.ptr)) }")
                                        builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                                    }
                                } else {
                                    when {
                                        fieldType == "CString" -> {
                                            builder.appendLine("get() = handle.useContents { this.$fieldName?.let { CString(NativeAddress(it)) } }")
                                            builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                                        }
                                        fieldType == "NativeAddress" -> {
                                            builder.appendLine("get() = handle.useContents { this.$fieldName?.let(::NativeAddress) ?: NativeAddress(0L) }")
                                            builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                                        }
                                        fieldType == "NativeAddress?" -> {
                                            builder.appendLine("get() = handle.useContents { this.$fieldName?.let(::NativeAddress) }")
                                            builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                                        }
                                        fieldType.endsWith("?") -> {
                                            val nonOpt = fieldType.removeSuffix("?")
                                            builder.appendLine("get() = handle.useContents { this.$fieldName?.let(::NativeAddress)?.let { $nonOpt(it) } }")
                                            builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                                        }
                                        else -> {
                                            builder.appendLine("get() = handle.useContents { this.$fieldName as $fieldType }")
                                            builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
                                        }
                                    }
                                }
                            }
                        }
                        builder.unindent()
                    }
                    builder.unindent()
                    builder.appendLine("    }")
                }

                // ByReference implementation using type-safe C-Interop delegation
                builder.appendLine()
                builder.appendLine("class ByReference(override val handler: NativeAddress) : $structName {")
                builder.indent()
                if (fields.isEmpty()) {
                    builder.appendLine("private val struct: COpaquePointer")
                    builder.appendLine("    get() = handler.pointer")
                } else {
                    builder.appendLine("private val struct: webgpu.native.$structName")
                    builder.appendLine("    get() = handler.pointer.reinterpret<webgpu.native.$structName>().pointed")
                }
                builder.appendLine()
                fields.forEach { field ->
                    val fieldName = field.name()
                    val fieldType = typeMapper.mapType(field.type())
                    if (fieldType == "CString") {
                        builder.appendLine("override var $fieldName: CString?")
                    } else if (fieldType.startsWith("ArrayHolder")) {
                        builder.appendLine("override var $fieldName: $fieldType?")
                    } else if (fieldType.endsWith("?") || fieldType == "NativeAddress") {
                        builder.appendLine("override var $fieldName: $fieldType")
                    } else {
                        builder.appendLine("override var $fieldName: $fieldType")
                    }
                    builder.indent()
                    when (fieldType) {
                        "CString" -> {
                            builder.appendLine("get() = struct.$fieldName?.let { CString(NativeAddress(it)) }")
                            builder.appendLine("set(value) { struct.$fieldName = value?.handler?.pointer?.takeIf { value.handler.rawValue != 0L }?.reinterpret() }")
                        }
                        "NativeAddress" -> {
                            builder.appendLine("get() = struct.$fieldName?.let(::NativeAddress) ?: NativeAddress(0L)")
                            builder.appendLine("set(value) { struct.$fieldName = value.pointer.takeIf { value.rawValue != 0L }?.reinterpret() }")
                        }
                        "NativeAddress?" -> {
                            builder.appendLine("get() = struct.$fieldName?.let(::NativeAddress)")
                            builder.appendLine("set(value) { struct.$fieldName = value?.pointer?.takeIf { value.rawValue != 0L }?.reinterpret() }")
                        }
                        "Boolean" -> {
                            builder.appendLine("get() = struct.$fieldName")
                            builder.appendLine("set(value) { struct.$fieldName = value }")
                        }
                        "Byte", "Short", "Int", "Long", "Float", "Double", "UByte", "UShort", "UInt", "ULong" -> {
                            builder.appendLine("get() = struct.$fieldName")
                            builder.appendLine("set(value) { struct.$fieldName = value }")
                        }
                        else -> {
                            if (typeMapper.isInlineStructOrUnion(field.type())) {
                                val isOpt = fieldType == "CString" || fieldType.startsWith("ArrayHolder") || fieldType.endsWith("?")
                                if (isOpt) {
                                    val nonOpt = fieldType.removeSuffix("?")
                                    builder.appendLine("get() = struct.$fieldName?.let(::NativeAddress)?.let { $nonOpt.ByReference(it) }")
                                    builder.appendLine("set(value) { struct.$fieldName = value?.handler?.pointer?.takeIf { value.handler.rawValue != 0L }?.reinterpret() }")
                                } else {
                                    builder.appendLine("get() = $fieldType.ByReference(NativeAddress(struct.$fieldName.ptr))")
                                    builder.appendLine("set(value) {")
                                    builder.indent()
                                    builder.appendLine("val destBytes = struct.$fieldName.ptr.reinterpret<ByteVar>()")
                                    builder.appendLine("val srcBytes = value.handler.pointer.reinterpret<ByteVar>()")
                                    builder.appendLine("val byteSize = sizeOf<webgpu.native.$fieldType>().toLong()")
                                    builder.appendLine("for (i in 0L until byteSize) {")
                                    builder.indent()
                                    builder.appendLine("destBytes[i.toInt()] = srcBytes[i.toInt()]")
                                    builder.unindent()
                                    builder.appendLine("}")
                                    builder.unindent()
                                    builder.appendLine("}")
                                }
                            } else {
                                when {
                                    fieldType == "CString" -> {
                                        builder.appendLine("get() = struct.$fieldName?.let { CString(NativeAddress(it)) }")
                                        builder.appendLine("set(value) { struct.$fieldName = value?.handler?.pointer?.takeIf { value.handler.rawValue != 0L }?.reinterpret() }")
                                    }
                                    fieldType == "NativeAddress" -> {
                                        builder.appendLine("get() = struct.$fieldName?.let(::NativeAddress) ?: NativeAddress(0L)")
                                        builder.appendLine("set(value) { struct.$fieldName = value.pointer.takeIf { value.rawValue != 0L }?.reinterpret() }")
                                    }
                                    fieldType == "NativeAddress?" -> {
                                        builder.appendLine("get() = struct.$fieldName?.let(::NativeAddress)")
                                        builder.appendLine("set(value) { struct.$fieldName = value?.pointer?.takeIf { value.rawValue != 0L }?.reinterpret() }")
                                    }
                                    fieldType.endsWith("?") -> {
                                        val nonOpt = fieldType.removeSuffix("?")
                                        builder.appendLine("get() = struct.$fieldName?.let(::NativeAddress)?.let { $nonOpt(it) }")
                                        builder.appendLine("set(value) { struct.$fieldName = value?.handler?.pointer?.takeIf { value.handler.rawValue != 0L }?.reinterpret() }")
                                    }
                                    else -> {
                                        builder.appendLine("get() = struct.$fieldName as $fieldType")
                                        builder.appendLine("set(value) { struct.$fieldName = value }")
                                    }
                                }
                            }
                        }
                    }
                    builder.unindent()
                }
                builder.unindent()
                builder.appendLine("}") // End ByReference
 
                builder.unindent()
                builder.appendLine("}") // End actual interface
                builder.appendLine()

                // Generate toCValue extension function for structure by-value passing
                if (fields.isNotEmpty()) {
                    builder.appendLine("fun $structName.toCValue(): CValue<webgpu.native.$structName> = cValue {")
                    builder.indent()
                    fields.forEach { field ->
                        val fieldName = field.name()
                        val fieldType = typeMapper.mapType(field.type())
                        if (typeMapper.isInlineStructOrUnion(field.type())) {
                            builder.appendLine("val dest_$fieldName = this.$fieldName.ptr.reinterpret<ByteVar>()")
                            builder.appendLine("val src_$fieldName = this@toCValue.$fieldName.handler.pointer.reinterpret<ByteVar>()")
                            builder.appendLine("val size_$fieldName = sizeOf<webgpu.native.$fieldType>().toLong()")
                            builder.appendLine("for (i in 0L until size_$fieldName) {")
                            builder.indent()
                            builder.appendLine("dest_$fieldName[i.toInt()] = src_$fieldName[i.toInt()]")
                            builder.unindent()
                            builder.appendLine("}")
                        } else {
                            when (fieldType) {
                                "CString" -> {
                                    builder.appendLine("this.$fieldName = this@toCValue.$fieldName?.handler?.pointer?.takeIf { this@toCValue.$fieldName?.handler?.rawValue != 0L }?.reinterpret()")
                                }
                                "NativeAddress" -> {
                                    builder.appendLine("this.$fieldName = this@toCValue.$fieldName.pointer?.takeIf { this@toCValue.$fieldName.rawValue != 0L }?.reinterpret()")
                                }
                                "NativeAddress?" -> {
                                    builder.appendLine("this.$fieldName = this@toCValue.$fieldName?.pointer?.takeIf { this@toCValue.$fieldName?.rawValue != 0L }?.reinterpret()")
                                }
                                "Boolean" -> {
                                    builder.appendLine("this.$fieldName = this@toCValue.$fieldName")
                                }
                                else -> {
                                    if (fieldType.endsWith("?")) {
                                        builder.appendLine("this.$fieldName = this@toCValue.$fieldName?.handler?.pointer?.takeIf { this@toCValue.$fieldName?.handler?.rawValue != 0L }?.reinterpret()")
                                    } else {
                                        builder.appendLine("this.$fieldName = this@toCValue.$fieldName")
                                    }
                                }
                            }
                        }
                    }
                    builder.unindent()
                    builder.appendLine("}")
                    builder.appendLine()
                }
            }
            Declaration.Scoped.Kind.TOPLEVEL -> {
                for (member in decl.members()) {
                    member.accept(this)
                }
            }
            else -> {}
        }

        if (decl.kind() == Declaration.Scoped.Kind.TOPLEVEL) {
            files.add(
                KotlinSourceFile(
                    targetPackage,
                    className + "Native",
                    builder.toString(),
                    sourceRoot = "nativeMain/kotlin",
                ),
            )
        }
    }

    override fun visitFunction(decl: Declaration.Function) {
        if (Skip.isPresent(decl)) return
        val returnType = typeMapper.mapFunctionType(decl.type().returnType())
        val params = decl.parameters().mapIndexed { index, param ->
            val name = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            "$name: ${typeMapper.mapFunctionType(param.type())}"
        }.joinToString(", ")
        val args = decl.parameters().mapIndexed { index, param ->
            val name = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            toNativeArgument(name, param.type())
        }.joinToString(", ")
        val call = "webgpu.native.${decl.name()}($args)"
        builder.appendLine("actual fun ${decl.name()}($params): $returnType {")
        builder.indent()
        emitFunctionReturn(decl.type().returnType(), returnType, call)
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
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

    private fun emitFunctionReturn(type: Type, returnType: String, call: String) {
        if (returnType == "Unit") {
            builder.appendLine(call)
            builder.appendLine("return")
            return
        }
        when {
            returnsStructByValue(type) -> builder.appendLine("return $returnType.ByValue($call)")
            returnType == "NativeAddress?" -> builder.appendLine("return $call?.let(::NativeAddress)")
            returnType == "CString?" -> builder.appendLine("return $call?.let(::NativeAddress)?.let(::CString)")
            returnType.endsWith("?") && returnsPointer(type) -> {
                val nonOpt = returnType.removeSuffix("?")
                builder.appendLine("return $call?.let(::NativeAddress)?.let { $nonOpt(it) }")
            }
            else -> builder.appendLine("return $call")
        }
    }

    private fun toNativeArgument(name: String, type: Type): String {
        val kmpType = typeMapper.mapFunctionType(type)
        return when {
            kmpType == "CString?" -> "$name?.handler?.pointer?.takeIf { $name.handler.rawValue != 0L }?.reinterpret()"
            typeMapper.callbackFunction(type) != null ->
                "$name?.pointer?.takeIf { $name.rawValue != 0L }?.reinterpret()"
            kmpType == "NativeAddress?" -> {
                val cast = when (name) {
                    "dynamicOffsets" -> "UIntVar"
                    "submissionIndex" -> "ULongVar"
                    else -> nativePointerVarType(type)
                }
                if (cast == null) {
                    "$name?.pointer?.takeIf { $name.rawValue != 0L }"
                } else {
                    "$name?.pointer?.takeIf { $name.rawValue != 0L }?.reinterpret<$cast>()"
                }
            }
            kmpType.endsWith("?") && returnsPointer(type) -> "$name?.handler?.pointer?.takeIf { $name.handler.rawValue != 0L }?.reinterpret()"
            returnsStructByValue(type) -> "$name.toCValue()"
            else -> name
        }
    }

    private fun returnsPointer(type: Type): Boolean = when {
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> true
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> returnsPointer(type.type())
        else -> false
    }

    private fun returnsStructByValue(type: Type): Boolean =
        !returnsPointer(type) &&
            typeMapper.mapFunctionType(type).let { it in generatedStructNames || it == "WGPUNativeDisplayHandle" }

    private fun nativePointerVarType(type: Type): String? {
        val pointee = when {
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> type.type()
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> return nativePointerVarType(type.type())
            else -> return null
        }
        return when {
            pointee is Type.Delegated && pointee.kind() == Type.Delegated.Kind.UNSIGNED -> {
                when ((pointee.type() as? Type.Primitive)?.kind()) {
                    Type.Primitive.Kind.Char -> "UByteVar"
                    Type.Primitive.Kind.Short -> "UShortVar"
                    Type.Primitive.Kind.Int -> "UIntVar"
                    Type.Primitive.Kind.Long, Type.Primitive.Kind.LongLong -> "ULongVar"
                    else -> null
                }
            }
            pointee is Type.Primitive -> {
                when (pointee.kind()) {
                    Type.Primitive.Kind.Char -> "ByteVar"
                    Type.Primitive.Kind.Short -> "ShortVar"
                    Type.Primitive.Kind.Int -> "IntVar"
                    Type.Primitive.Kind.Long, Type.Primitive.Kind.LongLong -> "LongVar"
                    Type.Primitive.Kind.Float -> "FloatVar"
                    Type.Primitive.Kind.Double -> "DoubleVar"
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun emitNativeDisplayHandle() {
        builder.appendLine("actual interface WGPUNativeDisplayHandle {")
        builder.indent()
        builder.appendLine("actual var type: WGPUNativeDisplayHandleType")
        builder.appendLine("actual val xlib: WGPUXlibDisplayHandle?")
        builder.appendLine("actual fun setXlib(value: WGPUXlibDisplayHandle)")
        builder.appendLine("actual val xcb: WGPUXcbDisplayHandle?")
        builder.appendLine("actual fun setXcb(value: WGPUXcbDisplayHandle)")
        builder.appendLine("actual val wayland: WGPUWaylandDisplayHandle?")
        builder.appendLine("actual fun setWayland(value: WGPUWaylandDisplayHandle)")
        builder.appendLine("actual val handler: NativeAddress")
        builder.appendLine("actual companion object {")
        builder.indent()
        builder.appendLine("actual operator fun invoke(address: NativeAddress): WGPUNativeDisplayHandle = ByReference(address)")
        builder.appendLine("actual fun allocate(allocator: MemoryAllocator): WGPUNativeDisplayHandle =")
        builder.appendLine("    ByReference(allocator.allocate(sizeOf<webgpu.native.WGPUNativeDisplayHandle>().toLong()))")
        builder.appendLine("actual fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, WGPUNativeDisplayHandle) -> Unit): ArrayHolder<WGPUNativeDisplayHandle> {")
        builder.indent()
        builder.appendLine("val byteSize = sizeOf<webgpu.native.WGPUNativeDisplayHandle>().toLong()")
        builder.appendLine("val segment = allocator.allocate(byteSize * size.toLong())")
        builder.appendLine("for (i in 0 until size.toInt()) {")
        builder.indent()
        builder.appendLine("val rawAddr = segment.rawValue + i.toLong() * byteSize")
        builder.appendLine("provider(i.toUInt(), ByReference(NativeAddress(rawAddr)))")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("return ArrayHolder(segment)")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
        builder.appendLine("value class ByValue(val handle: CValue<webgpu.native.WGPUNativeDisplayHandle>) : WGPUNativeDisplayHandle {")
        builder.indent()
        builder.appendLine("override val handler: NativeAddress")
        builder.appendLine("    get() = error(\"should not be call on CValue\")")
        emitNativeDisplayHandleNativeProperties("handle.useContents { this }", byValue = true)
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
        builder.appendLine("class ByReference(override val handler: NativeAddress) : WGPUNativeDisplayHandle {")
        builder.indent()
        builder.appendLine("private val struct: webgpu.native.WGPUNativeDisplayHandle")
        builder.appendLine("    get() = handler.pointer.reinterpret<webgpu.native.WGPUNativeDisplayHandle>().pointed")
        emitNativeDisplayHandleNativeProperties("struct", byValue = false)
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
        builder.appendLine("fun WGPUNativeDisplayHandle.toCValue(): CValue<webgpu.native.WGPUNativeDisplayHandle> = cValue {")
        builder.indent()
        builder.appendLine("this.type = this@toCValue.type")
        builder.appendLine("this@toCValue.xlib?.let {")
        builder.indent()
        builder.appendLine("val destBytes = this.data.xlib.ptr.reinterpret<ByteVar>()")
        builder.appendLine("val srcBytes = it.handler.pointer.reinterpret<ByteVar>()")
        builder.appendLine("for (i in 0 until sizeOf<webgpu.native.WGPUXlibDisplayHandle>()) destBytes[i] = srcBytes[i]")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("this@toCValue.xcb?.let {")
        builder.indent()
        builder.appendLine("val destBytes = this.data.xcb.ptr.reinterpret<ByteVar>()")
        builder.appendLine("val srcBytes = it.handler.pointer.reinterpret<ByteVar>()")
        builder.appendLine("for (i in 0 until sizeOf<webgpu.native.WGPUXcbDisplayHandle>()) destBytes[i] = srcBytes[i]")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("this@toCValue.wayland?.let {")
        builder.indent()
        builder.appendLine("val destBytes = this.data.wayland.ptr.reinterpret<ByteVar>()")
        builder.appendLine("val srcBytes = it.handler.pointer.reinterpret<ByteVar>()")
        builder.appendLine("for (i in 0 until sizeOf<webgpu.native.WGPUWaylandDisplayHandle>()) destBytes[i] = srcBytes[i]")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitNativeDisplayHandleNativeProperties(receiver: String, byValue: Boolean) {
        builder.appendLine("override var type: WGPUNativeDisplayHandleType")
        builder.indent()
        if (byValue) {
            builder.appendLine("get() = $receiver.type")
            builder.appendLine("set(value) { error(\"Setters not supported on ByValue\") }")
        } else {
            builder.appendLine("get() = $receiver.type")
            builder.appendLine("set(value) { $receiver.type = value }")
        }
        builder.unindent()
        listOf("xlib" to "WGPUXlibDisplayHandle", "xcb" to "WGPUXcbDisplayHandle", "wayland" to "WGPUWaylandDisplayHandle").forEach { (field, type) ->
            val setter = field.replaceFirstChar { it.titlecase() }
            builder.appendLine("override val $field: $type?")
            builder.indent()
            builder.appendLine("get() = if (type == WGPUNativeDisplayHandleType_$setter) $type.ByReference(NativeAddress($receiver.data.$field.ptr)) else null")
            builder.unindent()
            builder.appendLine("override fun set$setter(value: $type) {")
            builder.indent()
            if (byValue) {
                builder.appendLine("error(\"Setters not supported on ByValue\")")
            } else {
                builder.appendLine("$receiver.type = WGPUNativeDisplayHandleType_$setter")
                builder.appendLine("val destBytes = $receiver.data.$field.ptr.reinterpret<ByteVar>()")
                builder.appendLine("val srcBytes = value.handler.pointer.reinterpret<ByteVar>()")
                builder.appendLine("for (i in 0 until sizeOf<webgpu.native.$type>()) destBytes[i] = srcBytes[i]")
            }
            builder.unindent()
            builder.appendLine("}")
        }
    }

    private fun isOptionsStyle(name: String): Boolean =
        name.endsWith("Options") || name.endsWith("Flags") || name.endsWith("Mask") || name == "WGPUInstanceBackend" || name == "WGPUInstanceFlag" || name == "WGPUFlags"

}
