@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.pipeline.LayoutUtils
import org.graphiks.kextract.kotlin.utils.TypeMapper
import org.graphiks.kextract.pipeline.isEnum

class KotlinKmpAndroidBuilder(
    private val targetPackage: String,
    private val className: String
) : Declaration.Visitor<Unit> {

    private val builder = SourceBuilder()
    private val jnaBuilder = SourceBuilder()
    private val files = mutableListOf<KotlinSourceFile>()
    private val generatedNames = mutableSetOf<String>()
    private val generatedStructNames = mutableSetOf<String>()
    private val opaqueHandleAliases = mutableMapOf<String, String>()
    private val androidPackage = if (targetPackage.isEmpty()) "android" else "$targetPackage.android"
    private val typeMapper = KmpTypeMapper(opaqueHandleAliases, generatedStructNames, arraysAsHolders = false)

    init {
        if (targetPackage.isNotEmpty()) {
            builder.appendLine("package $targetPackage")
            builder.appendLine()
        }
        jnaBuilder.appendLine("package $androidPackage")
        jnaBuilder.appendLine()

        builder.appendLine("import io.ygdrasil.kffi.NativeAddress")
        builder.appendLine("import io.ygdrasil.kffi.CallbackHolder")
        builder.appendLine("import io.ygdrasil.kffi.CString")
        builder.appendLine("import io.ygdrasil.kffi.ArrayHolder")
        builder.appendLine("import io.ygdrasil.kffi.MemoryAllocator")
        builder.appendLine("import io.ygdrasil.kffi.toAddress")
        builder.appendLine()

        jnaBuilder.appendLine("import com.sun.jna.Pointer")
        jnaBuilder.appendLine("import com.sun.jna.Structure")
        jnaBuilder.appendLine("import com.sun.jna.Union")
        jnaBuilder.appendLine()
    }

    override fun visitScoped(decl: Declaration.Scoped) {
        if (Skip.isPresent(decl)) return
        when (decl.kind()) {
            Declaration.Scoped.Kind.STRUCT,
            Declaration.Scoped.Kind.UNION -> {
                val structName = decl.name()
                if (structName.isEmpty() || structName.contains("unnamed") || (!structName.startsWith("WGPU") && !structName.startsWith("wgpu"))) return
                if (structName.endsWith("Impl") && decl.members().isEmpty()) return
                if (!generatedNames.add(structName)) return
                generatedStructNames.add(structName)
                if (structName == "WGPUNativeDisplayHandle") {
                    emitNativeDisplayHandle()
                    return
                }

                val fields = decl.members().filterIsInstance<Declaration.Variable>().filterNot(Skip::isPresent)

                // 1. Generate the Bridge Actual Interface
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
                builder.appendLine("actual operator fun invoke(address: NativeAddress): $structName {")
                builder.indent()
                builder.appendLine("return ByReference($androidPackage.$structName.ByReference(address))")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine()
                builder.appendLine("actual fun allocate(allocator: MemoryAllocator): $structName {")
                builder.indent()
                builder.appendLine("val ref = $androidPackage.$structName.ByReference()")
                builder.appendLine("allocator.register(ref)")
                builder.appendLine("return ByReference(ref)")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine()
                builder.appendLine("actual fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, $structName) -> Unit): ArrayHolder<$structName> {")
                builder.indent()
                builder.appendLine("val ref = $androidPackage.$structName.ByValue()")
                builder.appendLine("val array = ref.toArray(size.toInt())")
                builder.appendLine("array.forEachIndexed { index, struct ->")
                builder.indent()
                builder.appendLine("provider(index.toUInt(), ByValue(struct as $androidPackage.$structName.ByValue))")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine("val pointer = if (size == 0u) com.sun.jna.Pointer.NULL else array.first().pointer")
                builder.appendLine("return ArrayHolder(pointer)")
                builder.unindent()
                builder.appendLine("}")
                builder.unindent()
                builder.appendLine("}") // End companion object

                // Generate ByReference Implementation
                builder.appendLine()
                builder.appendLine("class ByReference(val handle: $androidPackage.$structName.ByReference = $androidPackage.$structName.ByReference(com.sun.jna.Pointer.NULL)) : $structName {")
                builder.indent()
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
                    val inlineJnaType = inlineRecordJnaType(field.type())
                    if (decl.kind() == Declaration.Scoped.Kind.UNION) {
                        builder.indent()
                        emitUnionFieldAccessors(field)
                        builder.unindent()
                    } else if (inlineJnaType != null) {
                        builder.indent()
                        emitInlineRecordAccessors(fieldName, fieldType)
                        builder.unindent()
                    } else if (isStructType(field.type())) {
                        builder.indent()
                        val isOpt = fieldType == "CString" || fieldType.startsWith("ArrayHolder") || fieldType.endsWith("?")
                        val nonOpt = fieldType.removeSuffix("?")
                        val jnaType = mapJnaType(field.type())
                        if (fieldType.startsWith("ArrayHolder")) {
                            builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference(it) }")
                            builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle }")
                        } else if (nonOpt == "WGPUNativeDisplayHandle") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($androidPackage.WGPUNativeDisplayHandle.ByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? WGPUNativeDisplayHandle.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($androidPackage.WGPUNativeDisplayHandle.ByReference(it)) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as WGPUNativeDisplayHandle.ByReference).handle.pointer }")
                            }
                        } else if (jnaType == "Pointer?") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference($androidPackage.$nonOpt.ByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { $fieldType.ByReference($androidPackage.$fieldType.ByReference(it)) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as $fieldType.ByReference).handle.pointer }")
                            }
                        } else {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference(it) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { $fieldType.ByReference(it) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as $fieldType.ByReference).handle }")
                            }
                        }
                        builder.unindent()
                    } else {
                        builder.indent()
                        val isEnum = typeMapper.isEnumType(field.type())
                        when {
                            fieldType == "CString" -> {
                                builder.appendLine("get() = handle.$fieldName?.let(::CString)")
                                builder.appendLine("set(value) { handle.$fieldName = value?.handler }")
                            }
                            fieldType == "NativeAddress" -> {
                                builder.appendLine("get() = handle.$fieldName ?: com.sun.jna.Pointer.NULL")
                                builder.appendLine("set(value) { handle.$fieldName = value }")
                            }
                            fieldType == "NativeAddress?" -> {
                                builder.appendLine("get() = handle.$fieldName")
                                builder.appendLine("set(value) { handle.$fieldName = value }")
                            }
                            fieldType == "Boolean" -> {
                                builder.appendLine("get() = handle.$fieldName")
                                builder.appendLine("set(value) { handle.$fieldName = value }")
                            }
                            isEnum || fieldType == "UInt" -> {
                                builder.appendLine("get() = handle.$fieldName.toUInt() as $fieldType")
                                builder.appendLine("set(value) { handle.$fieldName = value.toInt() }")
                            }
                            fieldType == "ULong" || fieldType.endsWith("Flags") || fieldType.endsWith("Usage") -> {
                                builder.appendLine("get() = handle.$fieldName.toULong() as $fieldType")
                                builder.appendLine("set(value) { handle.$fieldName = value.toLong() }")
                            }
                            fieldType == "UShort" -> {
                                builder.appendLine("get() = handle.$fieldName.toUShort() as $fieldType")
                                builder.appendLine("set(value) { handle.$fieldName = value.toShort() }")
                            }
                            fieldType == "UByte" -> {
                                builder.appendLine("get() = handle.$fieldName.toUByte() as $fieldType")
                                builder.appendLine("set(value) { handle.$fieldName = value.toByte() }")
                            }
                            else -> {
                                val isPrimitiveOrKffi = fieldType in listOf("Byte", "Short", "Int", "Long", "Float", "Double", "Boolean", "CString", "NativeAddress")
                                if (!isPrimitiveOrKffi) {
                                    if (fieldType.endsWith("?")) {
                                        val nonOpt = fieldType.removeSuffix("?")
                                        builder.appendLine("get() = handle.$fieldName?.let { $nonOpt(it) }")
                                        builder.appendLine("set(value) { handle.$fieldName = value?.handler }")
                                    } else {
                                        builder.appendLine("get() = handle.$fieldName?.let { $fieldType(it) } ?: error(\"$fieldName is null\")")
                                        builder.appendLine("set(value) { handle.$fieldName = value.handler }")
                                    }
                                } else {
                                    builder.appendLine("get() = handle.$fieldName as $fieldType")
                                    builder.appendLine("set(value) { handle.$fieldName = value }")
                                }
                            }
                        }
                        builder.unindent()
                    }
                }
                builder.appendLine("override val handler: NativeAddress")
                builder.indent()
                builder.appendLine("get() {")
                builder.indent()
                builder.appendLine("handle.write()")
                builder.appendLine("return handle.pointer")
                builder.unindent()
                builder.appendLine("}")
                builder.unindent()
                builder.unindent()
                builder.appendLine("}") // End ByReference

                // Generate ByValue Implementation
                builder.appendLine()
                builder.appendLine("class ByValue(val handle: $androidPackage.$structName.ByValue = $androidPackage.$structName.ByValue(com.sun.jna.Pointer.NULL)) : $structName {")
                builder.indent()
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
                    val inlineJnaType = inlineRecordJnaType(field.type())
                    if (decl.kind() == Declaration.Scoped.Kind.UNION) {
                        builder.indent()
                        emitUnionFieldAccessors(field)
                        builder.unindent()
                    } else if (inlineJnaType != null) {
                        builder.indent()
                        emitInlineRecordAccessors(fieldName, fieldType)
                        builder.unindent()
                    } else if (isStructType(field.type())) {
                        builder.indent()
                        val isOpt = fieldType == "CString" || fieldType.startsWith("ArrayHolder") || fieldType.endsWith("?")
                        val nonOpt = fieldType.removeSuffix("?")
                        val jnaType = mapJnaType(field.type())
                        if (fieldType.startsWith("ArrayHolder")) {
                            builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference(it) }")
                            builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle }")
                        } else if (nonOpt == "WGPUNativeDisplayHandle") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($androidPackage.WGPUNativeDisplayHandle.ByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? WGPUNativeDisplayHandle.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($androidPackage.WGPUNativeDisplayHandle.ByReference(it)) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as WGPUNativeDisplayHandle.ByReference).handle.pointer }")
                            }
                        } else if (jnaType == "Pointer?") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference($androidPackage.$nonOpt.ByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { $fieldType.ByReference($androidPackage.$fieldType.ByReference(it)) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as $fieldType.ByReference).handle.pointer }")
                            }
                        } else {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference(it) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { $fieldType.ByReference(it) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as $fieldType.ByReference).handle }")
                            }
                        }
                        builder.unindent()
                    } else {
                        builder.indent()
                        val isEnum = typeMapper.isEnumType(field.type())
                        when {
                            fieldType == "CString" -> {
                                builder.appendLine("get() = handle.$fieldName?.let(::CString)")
                                builder.appendLine("set(value) { handle.$fieldName = value?.handler }")
                            }
                            fieldType == "NativeAddress" -> {
                                builder.appendLine("get() = handle.$fieldName ?: com.sun.jna.Pointer.NULL")
                                builder.appendLine("set(value) { handle.$fieldName = value }")
                            }
                            fieldType == "NativeAddress?" -> {
                                builder.appendLine("get() = handle.$fieldName")
                                builder.appendLine("set(value) { handle.$fieldName = value }")
                            }
                            fieldType == "Boolean" -> {
                                builder.appendLine("get() = handle.$fieldName")
                                builder.appendLine("set(value) { handle.$fieldName = value }")
                            }
                            isEnum || fieldType == "UInt" -> {
                                builder.appendLine("get() = handle.$fieldName.toUInt() as $fieldType")
                                builder.appendLine("set(value) { handle.$fieldName = value.toInt() }")
                            }
                            fieldType == "ULong" || fieldType.endsWith("Flags") || fieldType.endsWith("Usage") -> {
                                builder.appendLine("get() = handle.$fieldName.toULong() as $fieldType")
                                builder.appendLine("set(value) { handle.$fieldName = value.toLong() }")
                            }
                            fieldType == "UShort" -> {
                                builder.appendLine("get() = handle.$fieldName.toUShort() as $fieldType")
                                builder.appendLine("set(value) { handle.$fieldName = value.toShort() }")
                            }
                            fieldType == "UByte" -> {
                                builder.appendLine("get() = handle.$fieldName.toUByte() as $fieldType")
                                builder.appendLine("set(value) { handle.$fieldName = value.toByte() }")
                            }
                            else -> {
                                val isPrimitiveOrKffi = fieldType in listOf("Byte", "Short", "Int", "Long", "Float", "Double", "Boolean", "CString", "NativeAddress")
                                if (!isPrimitiveOrKffi) {
                                    if (fieldType.endsWith("?")) {
                                        val nonOpt = fieldType.removeSuffix("?")
                                        builder.appendLine("get() = handle.$fieldName?.let { $nonOpt(it) }")
                                        builder.appendLine("set(value) { handle.$fieldName = value?.handler }")
                                    } else {
                                        builder.appendLine("get() = handle.$fieldName?.let { $fieldType(it) } ?: error(\"$fieldName is null\")")
                                        builder.appendLine("set(value) { handle.$fieldName = value.handler }")
                                    }
                                } else {
                                    builder.appendLine("get() = handle.$fieldName as $fieldType")
                                    builder.appendLine("set(value) { handle.$fieldName = value }")
                                }
                            }
                        }
                        builder.unindent()
                    }
                }
                builder.appendLine("override val handler: NativeAddress")
                builder.indent()
                builder.appendLine("get() {")
                builder.indent()
                builder.appendLine("handle.write()")
                builder.appendLine("return handle.pointer")
                builder.unindent()
                builder.appendLine("}")
                builder.unindent()
                builder.unindent()
                builder.appendLine("}") // End ByValue

                builder.unindent()
                builder.appendLine("}") // End actual interface
                builder.appendLine()


                // 2. Generate the RAW JNA class
                val jnaBase = if (decl.kind() == Declaration.Scoped.Kind.UNION) "Union" else "Structure"
                jnaBuilder.appendLine("open class $structName(pointer: Pointer? = null) : $jnaBase(pointer) {")
                jnaBuilder.indent()
                fields.forEach { field ->
                    val fieldName = field.name()
                    val fieldType = mapJnaType(field.type())
                    jnaBuilder.appendLine("@JvmField var $fieldName: ${fieldType} = ${getDefaultJnaValue(field.type())}")
                }
                jnaBuilder.appendLine("override fun getFieldOrder() = listOf<String>(${fields.joinToString(", ") { "\"${it.name()}\"" }})")

                jnaBuilder.appendLine("class ByReference(pointer: Pointer? = null) : $structName(pointer), Structure.ByReference")
                jnaBuilder.appendLine("class ByValue(pointer: Pointer? = null) : $structName(pointer), Structure.ByValue")
                jnaBuilder.unindent()
                jnaBuilder.appendLine("}")
                jnaBuilder.appendLine()
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
                    className + "Android",
                    builder.toString(),
                    sourceRoot = "androidMain/kotlin",
                ),
            )
            files.add(
                KotlinSourceFile(
                    androidPackage,
                    className,
                    jnaBuilder.toString(),
                    sourceRoot = "androidMain/kotlin",
                ),
            )
        }
    }

    override fun visitFunction(decl: Declaration.Function) {
        if (Skip.isPresent(decl)) return
        if (!decl.name().startsWith("wgpu")) return
        val returnType = typeMapper.mapFunctionType(decl.type().returnType())
        val params = decl.parameters().mapIndexed { index, param ->
            val name = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            "$name: ${typeMapper.mapFunctionType(param.type())}"
        }.joinToString(", ")
        builder.appendLine("actual fun ${decl.name()}($params): $returnType =")
        builder.indent()
        builder.appendLine("error(\"${decl.name()} is not implemented for Android/JNA generated bindings\")")
        builder.unindent()
        builder.appendLine()
    }
    override fun visitVariable(decl: Declaration.Variable) {}
    override fun visitTypedef(decl: Declaration.Typedef) {
        if (Skip.isPresent(decl)) return
        val name = decl.name()
        if (name.isEmpty() || !name.startsWith("WGPU")) return
        if (name.endsWith("Callback")) typeMapper.callbackFunction(decl.type())?.let { function ->
            if (!generatedNames.add(name)) return
            emitCallbackUnsupportedActual(name, function)
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

    private fun emitCallbackUnsupportedActual(name: String, function: Type.Function) {
        builder.appendLine("actual class $name private constructor(actual val handler: NativeAddress) : AutoCloseable {")
        builder.indent()
        builder.appendLine("actual override fun close() = Unit")
        builder.appendLine("actual companion object {")
        builder.indent()
        builder.appendLine("actual fun allocate(callback: ${typeMapper.callbackLambdaType(function)}): $name =")
        builder.indent()
        builder.appendLine("error(\"$name allocation is not implemented on Android\")")
        builder.unindent()
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
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
        builder.appendLine("actual operator fun invoke(address: NativeAddress): WGPUNativeDisplayHandle = ByReference($androidPackage.WGPUNativeDisplayHandle.ByReference(address))")
        builder.appendLine("actual fun allocate(allocator: MemoryAllocator): WGPUNativeDisplayHandle {")
        builder.indent()
        builder.appendLine("val ref = $androidPackage.WGPUNativeDisplayHandle.ByReference()")
        builder.appendLine("allocator.register(ref)")
        builder.appendLine("return ByReference(ref)")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("actual fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, WGPUNativeDisplayHandle) -> Unit): ArrayHolder<WGPUNativeDisplayHandle> {")
        builder.indent()
        builder.appendLine("val ref = $androidPackage.WGPUNativeDisplayHandle.ByValue()")
        builder.appendLine("val array = ref.toArray(size.toInt())")
        builder.appendLine("array.forEachIndexed { index, struct -> provider(index.toUInt(), ByValue(struct as $androidPackage.WGPUNativeDisplayHandle.ByValue)) }")
        builder.appendLine("val pointer = if (size == 0u) com.sun.jna.Pointer.NULL else array.first().pointer")
        builder.appendLine("return ArrayHolder(pointer)")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        emitNativeDisplayHandleAndroidImpl("ByReference", "$androidPackage.WGPUNativeDisplayHandle.ByReference")
        emitNativeDisplayHandleAndroidImpl("ByValue", "$androidPackage.WGPUNativeDisplayHandle.ByValue")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()

        jnaBuilder.appendLine("open class WGPUNativeDisplayHandle(pointer: Pointer? = null) : Structure(pointer) {")
        jnaBuilder.indent()
        jnaBuilder.appendLine("@JvmField var type: Int = 0")
        jnaBuilder.appendLine("@JvmField var data: Data = Data()")
        jnaBuilder.appendLine("override fun getFieldOrder() = listOf<String>(\"type\", \"data\")")
        jnaBuilder.appendLine("class Data : com.sun.jna.Union(), Structure.ByValue {")
        jnaBuilder.indent()
        jnaBuilder.appendLine("@JvmField var xlib: WGPUXlibDisplayHandle.ByValue = WGPUXlibDisplayHandle.ByValue()")
        jnaBuilder.appendLine("@JvmField var xcb: WGPUXcbDisplayHandle.ByValue = WGPUXcbDisplayHandle.ByValue()")
        jnaBuilder.appendLine("@JvmField var wayland: WGPUWaylandDisplayHandle.ByValue = WGPUWaylandDisplayHandle.ByValue()")
        jnaBuilder.unindent()
        jnaBuilder.appendLine("}")
        jnaBuilder.appendLine("class ByReference(pointer: Pointer? = null) : WGPUNativeDisplayHandle(pointer), Structure.ByReference")
        jnaBuilder.appendLine("class ByValue(pointer: Pointer? = null) : WGPUNativeDisplayHandle(pointer), Structure.ByValue")
        jnaBuilder.unindent()
        jnaBuilder.appendLine("}")
        jnaBuilder.appendLine()
    }

    private fun emitNativeDisplayHandleAndroidImpl(name: String, handleType: String) {
        builder.appendLine("class $name(val handle: $handleType = $handleType(com.sun.jna.Pointer.NULL)) : WGPUNativeDisplayHandle {")
        builder.indent()
        builder.appendLine("override var type: WGPUNativeDisplayHandleType")
        builder.indent()
        builder.appendLine("get() { handle.read(); return handle.type.toUInt() as WGPUNativeDisplayHandleType }")
        builder.appendLine("set(value) { handle.type = value.toInt(); handle.write() }")
        builder.unindent()
        listOf("xlib" to "WGPUXlibDisplayHandle", "xcb" to "WGPUXcbDisplayHandle", "wayland" to "WGPUWaylandDisplayHandle").forEach { (field, type) ->
            val setter = field.replaceFirstChar { it.titlecase() }
            builder.appendLine("override val $field: $type?")
            builder.indent()
            builder.appendLine("get() {")
            builder.indent()
            builder.appendLine("handle.read()")
            builder.appendLine("if (type != WGPUNativeDisplayHandleType_$setter) return null")
            builder.appendLine("handle.data.readField(\"$field\")")
            builder.appendLine("return $type.ByValue(handle.data.$field)")
            builder.unindent()
            builder.appendLine("}")
            builder.unindent()
            builder.appendLine("override fun set$setter(value: $type) {")
            builder.indent()
            builder.appendLine("handle.type = WGPUNativeDisplayHandleType_$setter.toInt()")
            builder.appendLine("val copy = $androidPackage.$type.ByValue(value.handler)")
            builder.appendLine("copy.read()")
            builder.appendLine("handle.data.$field = copy")
            builder.appendLine("handle.data.writeField(\"$field\")")
            builder.appendLine("handle.writeField(\"type\")")
            builder.appendLine("handle.writeField(\"data\")")
            builder.unindent()
            builder.appendLine("}")
        }
        builder.appendLine("override val handler: NativeAddress")
        builder.indent()
        builder.appendLine("get() { handle.write(); return handle.pointer }")
        builder.unindent()
        builder.unindent()
        builder.appendLine("}")
    }

    private fun isStructType(type: Type): Boolean = when {
        type is Type.Declared -> {
            val tree = type.tree()
            (tree.kind() == Declaration.Scoped.Kind.STRUCT || tree.kind() == Declaration.Scoped.Kind.UNION) &&
                    tree.members().filterIsInstance<Declaration.Variable>().isNotEmpty()
        }
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> {
            val inner = type.type()
            isStructType(inner)
        }
        else -> false
    }

    private fun isOptionsStyle(name: String): Boolean =
        name.endsWith("Options") || name.endsWith("Flags") || name.endsWith("Mask") || name == "WGPUInstanceBackend" || name == "WGPUInstanceFlag" || name == "WGPUFlags"

    private fun inlineRecordJnaType(type: Type): String? =
        canonicalRecordName(type)
            ?.takeIf { it.isNotEmpty() && !it.contains("unnamed") }
            ?.let { "$androidPackage.$it.ByValue" }

    private fun canonicalRecordName(type: Type): String? = when {
        type is Type.Declared && isStructType(type) -> type.tree().name()
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> canonicalRecordName(type.type())
        else -> null
    }

    private fun emitInlineRecordAccessors(fieldName: String, fieldType: String) {
        builder.appendLine("get() {")
        builder.indent()
        builder.appendLine("handle.readField(\"$fieldName\")")
        builder.appendLine("return $fieldType.ByValue(handle.$fieldName)")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("set(value) {")
        builder.indent()
        builder.appendLine("val bytes = value.handler.getByteArray(0, handle.$fieldName.size())")
        builder.appendLine("handle.readField(\"$fieldName\")")
        builder.appendLine("handle.$fieldName.pointer.write(0, bytes, 0, bytes.size)")
        builder.appendLine("handle.readField(\"$fieldName\")")
        builder.unindent()
        builder.appendLine("}")
    }

    private fun emitUnionFieldAccessors(field: Declaration.Variable) {
        val fieldName = field.name()
        val fieldType = typeMapper.mapType(field.type())
        val inlineJnaType = inlineRecordJnaType(field.type())
        if (inlineJnaType != null) {
            emitInlineRecordAccessors(fieldName, fieldType)
            return
        }

        val getter = when {
            fieldType == "CString" -> "handle.$fieldName?.let(::CString)"
            fieldType == "NativeAddress" -> "handle.$fieldName ?: com.sun.jna.Pointer.NULL"
            fieldType == "NativeAddress?" -> "handle.$fieldName"
            fieldType == "Boolean" -> "handle.$fieldName != 0"
            typeMapper.isEnumType(field.type()) || fieldType == "UInt" ->
                "handle.$fieldName.toUInt() as $fieldType"
            fieldType == "ULong" || fieldType.endsWith("Flags") || fieldType.endsWith("Usage") ->
                "handle.$fieldName.toULong() as $fieldType"
            fieldType == "UShort" -> "handle.$fieldName.toUShort() as $fieldType"
            fieldType == "UByte" -> "handle.$fieldName.toUByte() as $fieldType"
            fieldType.startsWith("ArrayHolder") -> "handle.$fieldName?.let { ArrayHolder(it) }"
            fieldType !in listOf("Byte", "Short", "Int", "Long", "Float", "Double") && fieldType.endsWith("?") -> {
                val nonOpt = fieldType.removeSuffix("?")
                "handle.$fieldName?.let { $nonOpt(it) }"
            }
            fieldType !in listOf("Byte", "Short", "Int", "Long", "Float", "Double") ->
                "handle.$fieldName?.let { $fieldType(it) } ?: error(\"$fieldName is null\")"
            else -> "handle.$fieldName as $fieldType"
        }
        val assignment = when {
            fieldType == "NativeAddress" || fieldType == "NativeAddress?" ->
                "handle.$fieldName = value"
            fieldType == "Boolean" -> "handle.$fieldName = if (value) 1 else 0"
            fieldType == "CString" || fieldType.startsWith("ArrayHolder") || fieldType.endsWith("?") ->
                "handle.$fieldName = value?.handler"
            typeMapper.isEnumType(field.type()) || fieldType == "UInt" ->
                "handle.$fieldName = value.toInt()"
            fieldType == "ULong" || fieldType.endsWith("Flags") || fieldType.endsWith("Usage") ->
                "handle.$fieldName = value.toLong()"
            fieldType == "UShort" -> "handle.$fieldName = value.toShort()"
            fieldType == "UByte" -> "handle.$fieldName = value.toByte()"
            fieldType !in listOf("Byte", "Short", "Int", "Long", "Float", "Double") ->
                "handle.$fieldName = value.handler"
            else -> "handle.$fieldName = value"
        }

        builder.appendLine("get() {")
        builder.indent()
        builder.appendLine("handle.readField(\"$fieldName\")")
        builder.appendLine("return $getter")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("set(value) {")
        builder.indent()
        builder.appendLine(assignment)
        builder.appendLine("handle.writeField(\"$fieldName\")")
        builder.unindent()
        builder.appendLine("}")
    }

    private fun mapJnaType(type: Type): String {
        inlineRecordJnaType(type)?.let { return it }
        return when {
        typeMapper.isEnumType(type) -> "Int"
        type is Type.Primitive -> mapJnaPrimitive(type.kind())
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
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> "Pointer?"
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> {
            val inner = type.type()
            when {
                typeMapper.isEnumType(inner) -> "Int"
                isStructType(inner) -> "Pointer?"
                inner is Type.Primitive -> mapJnaPrimitive(inner.kind())
                inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.UNSIGNED -> {
                    val innerInner = inner.type()
                    if (innerInner is Type.Primitive) {
                        when (innerInner.kind()) {
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
                else -> "Pointer?"
            }
        }
        type is Type.Declared -> {
            val tree = type.tree()
            if (tree.kind() == Declaration.Scoped.Kind.STRUCT || tree.kind() == Declaration.Scoped.Kind.UNION) {
                "Pointer?"
            } else if (tree.kind() == Declaration.Scoped.Kind.ENUM) {
                "Int"
            } else {
                "Pointer?"
            }
        }
        else -> "Pointer?"
    }
    }

    private fun mapJnaPrimitive(kind: Type.Primitive.Kind): String = when (kind) {
        Type.Primitive.Kind.Bool -> "Int"
        Type.Primitive.Kind.Char -> "Byte"
        Type.Primitive.Kind.Short -> "Short"
        Type.Primitive.Kind.Int -> "Int"
        Type.Primitive.Kind.Long, Type.Primitive.Kind.LongLong -> "Long"
        Type.Primitive.Kind.Float -> "Float"
        Type.Primitive.Kind.Double -> "Double"
        else -> "Pointer?"
    }

    private fun getDefaultJnaValue(type: Type): String {
        val jnaType = mapJnaType(type)
        return when {
            jnaType.endsWith(".ByValue") -> "$jnaType()"
            else -> when (jnaType) {
            "Int" -> "0"
            "Long" -> "0L"
            "Byte" -> "0"
            "Short" -> "0"
            "Float" -> "0.0f"
            "Double" -> "0.0"
            else -> "null"
            }
        }
    }
}
