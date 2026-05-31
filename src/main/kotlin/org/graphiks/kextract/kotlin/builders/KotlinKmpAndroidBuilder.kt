package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
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

    init {
        if (targetPackage.isNotEmpty()) {
            builder.appendLine("package $targetPackage")
            builder.appendLine()
            jnaBuilder.appendLine("package $targetPackage.android")
            jnaBuilder.appendLine()
        }

        builder.appendLine("import io.ygdrasil.kffi.NativeAddress")
        builder.appendLine("import io.ygdrasil.kffi.CallbackHolder")
        builder.appendLine("import io.ygdrasil.kffi.CString")
        builder.appendLine("import io.ygdrasil.kffi.ArrayHolder")
        builder.appendLine("import io.ygdrasil.kffi.MemoryAllocator")
        builder.appendLine("import io.ygdrasil.kffi.toAddress")
        builder.appendLine()

        jnaBuilder.appendLine("import com.sun.jna.Pointer")
        jnaBuilder.appendLine("import com.sun.jna.Structure")
        jnaBuilder.appendLine()
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
                    emitNativeDisplayHandle()
                    return
                }

                val fields = decl.members().filterIsInstance<Declaration.Variable>()

                // 1. Generate the Bridge Actual Interface
                builder.appendLine("actual interface $structName {")
                builder.indent()

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
                builder.appendLine("actual val handler: NativeAddress")

                // Companion object
                builder.appendLine("actual companion object {")
                builder.indent()
                builder.appendLine("actual operator fun invoke(address: NativeAddress): $structName {")
                builder.indent()
                builder.appendLine("return ByReference($targetPackage.android.$structName.ByReference(address))")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine()
                builder.appendLine("actual fun allocate(allocator: MemoryAllocator): $structName {")
                builder.indent()
                builder.appendLine("val ref = $targetPackage.android.$structName.ByReference()")
                builder.appendLine("allocator.register(ref)")
                builder.appendLine("return ByReference(ref)")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine()
                builder.appendLine("actual fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, $structName) -> Unit): ArrayHolder<$structName> {")
                builder.indent()
                builder.appendLine("val ref = $targetPackage.android.$structName.ByValue()")
                builder.appendLine("val array = ref.toArray(size.toInt())")
                builder.appendLine("array.forEachIndexed { index, struct ->")
                builder.indent()
                builder.appendLine("provider(index.toUInt(), ByValue(struct as $targetPackage.android.$structName.ByValue))")
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
                builder.appendLine("class ByReference(val handle: $targetPackage.android.$structName.ByReference = $targetPackage.android.$structName.ByReference(com.sun.jna.Pointer.NULL)) : $structName {")
                builder.indent()
                fields.forEach { field ->
                    val fieldName = field.name()
                    val fieldType = mapKmpType(field.type())
                    if (fieldType == "CString") {
                        builder.appendLine("override var $fieldName: CString?")
                    } else if (fieldType.startsWith("ArrayHolder")) {
                        builder.appendLine("override var $fieldName: $fieldType?")
                    } else if (fieldType.endsWith("?") || fieldType == "NativeAddress") {
                        builder.appendLine("override var $fieldName: $fieldType")
                    } else {
                        builder.appendLine("override var $fieldName: $fieldType")
                    }
                    if (isStructType(field.type())) {
                        builder.indent()
                        val isOpt = fieldType == "CString" || fieldType.startsWith("ArrayHolder") || fieldType.endsWith("?")
                        val nonOpt = fieldType.removeSuffix("?")
                        val jnaType = mapJnaType(field.type())
                        if (fieldType.startsWith("ArrayHolder")) {
                            builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference(it) }")
                            builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle }")
                        } else if (nonOpt == "WGPUNativeDisplayHandle") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($targetPackage.android.WGPUNativeDisplayHandle.ByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? WGPUNativeDisplayHandle.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($targetPackage.android.WGPUNativeDisplayHandle.ByReference(it)) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as WGPUNativeDisplayHandle.ByReference).handle.pointer }")
                            }
                        } else if (jnaType == "Pointer?") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference($targetPackage.android.$nonOpt.ByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { $fieldType.ByReference($targetPackage.android.$fieldType.ByReference(it)) } ?: error(\"$fieldName is null\")")
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
                        val isEnum = isEnumType(field.type())
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
                builder.appendLine("class ByValue(val handle: $targetPackage.android.$structName.ByValue = $targetPackage.android.$structName.ByValue(com.sun.jna.Pointer.NULL)) : $structName {")
                builder.indent()
                fields.forEach { field ->
                    val fieldName = field.name()
                    val fieldType = mapKmpType(field.type())
                    if (fieldType == "CString") {
                        builder.appendLine("override var $fieldName: CString?")
                    } else if (fieldType.startsWith("ArrayHolder")) {
                        builder.appendLine("override var $fieldName: $fieldType?")
                    } else if (fieldType.endsWith("?") || fieldType == "NativeAddress") {
                        builder.appendLine("override var $fieldName: $fieldType")
                    } else {
                        builder.appendLine("override var $fieldName: $fieldType")
                    }
                    if (isStructType(field.type())) {
                        builder.indent()
                        val isOpt = fieldType == "CString" || fieldType.startsWith("ArrayHolder") || fieldType.endsWith("?")
                        val nonOpt = fieldType.removeSuffix("?")
                        val jnaType = mapJnaType(field.type())
                        if (fieldType.startsWith("ArrayHolder")) {
                            builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference(it) }")
                            builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle }")
                        } else if (nonOpt == "WGPUNativeDisplayHandle") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($targetPackage.android.WGPUNativeDisplayHandle.ByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? WGPUNativeDisplayHandle.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($targetPackage.android.WGPUNativeDisplayHandle.ByReference(it)) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as WGPUNativeDisplayHandle.ByReference).handle.pointer }")
                            }
                        } else if (jnaType == "Pointer?") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference($targetPackage.android.$nonOpt.ByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { $fieldType.ByReference($targetPackage.android.$fieldType.ByReference(it)) } ?: error(\"$fieldName is null\")")
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
                        val isEnum = isEnumType(field.type())
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
                jnaBuilder.appendLine("open class $structName(pointer: Pointer? = null) : Structure(pointer) {")
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
            files.add(KotlinSourceFile(targetPackage, className + "Android", builder.toString()))
            files.add(KotlinSourceFile(targetPackage + ".android", className, jnaBuilder.toString()))
        }
    }

    override fun visitFunction(decl: Declaration.Function) {
        if (!decl.name().startsWith("wgpu")) return
        val returnType = mapKmpFunctionType(decl.type().returnType())
        val params = decl.parameters().mapIndexed { index, param ->
            val name = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            "$name: ${mapKmpFunctionType(param.type())}"
        }.joinToString(", ")
        builder.appendLine("actual fun ${decl.name()}($params): $returnType =")
        builder.indent()
        builder.appendLine("error(\"${decl.name()} is not implemented for Android/JNA generated bindings\")")
        builder.unindent()
        builder.appendLine()
    }
    override fun visitVariable(decl: Declaration.Variable) {}
    override fun visitTypedef(decl: Declaration.Typedef) {
        val name = decl.name()
        if (name.isEmpty() || !name.startsWith("WGPU")) return
        if (name.endsWith("Callback")) decl.type().callbackFunction()?.let { function ->
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
        builder.appendLine("actual fun allocate(callback: ${callbackLambdaType(function)}): $name =")
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
        builder.appendLine("actual operator fun invoke(address: NativeAddress): WGPUNativeDisplayHandle = ByReference($targetPackage.android.WGPUNativeDisplayHandle.ByReference(address))")
        builder.appendLine("actual fun allocate(allocator: MemoryAllocator): WGPUNativeDisplayHandle {")
        builder.indent()
        builder.appendLine("val ref = $targetPackage.android.WGPUNativeDisplayHandle.ByReference()")
        builder.appendLine("allocator.register(ref)")
        builder.appendLine("return ByReference(ref)")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("actual fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, WGPUNativeDisplayHandle) -> Unit): ArrayHolder<WGPUNativeDisplayHandle> {")
        builder.indent()
        builder.appendLine("val ref = $targetPackage.android.WGPUNativeDisplayHandle.ByValue()")
        builder.appendLine("val array = ref.toArray(size.toInt())")
        builder.appendLine("array.forEachIndexed { index, struct -> provider(index.toUInt(), ByValue(struct as $targetPackage.android.WGPUNativeDisplayHandle.ByValue)) }")
        builder.appendLine("val pointer = if (size == 0u) com.sun.jna.Pointer.NULL else array.first().pointer")
        builder.appendLine("return ArrayHolder(pointer)")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        emitNativeDisplayHandleAndroidImpl("ByReference", "$targetPackage.android.WGPUNativeDisplayHandle.ByReference")
        emitNativeDisplayHandleAndroidImpl("ByValue", "$targetPackage.android.WGPUNativeDisplayHandle.ByValue")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()

        jnaBuilder.appendLine("open class WGPUNativeDisplayHandle(pointer: Pointer? = null) : Structure(pointer) {")
        jnaBuilder.indent()
        jnaBuilder.appendLine("@JvmField var type: Int = 0")
        jnaBuilder.appendLine("@JvmField var data: Data = Data()")
        jnaBuilder.appendLine("override fun getFieldOrder() = listOf<String>(\"type\", \"data\")")
        jnaBuilder.appendLine("class Data : com.sun.jna.Union() {")
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
            builder.appendLine("handle.data.setType($targetPackage.android.$type.ByValue::class.java)")
            builder.appendLine("handle.data.read()")
            builder.appendLine("return $type.ByReference($targetPackage.android.$type.ByReference(handle.data.$field.pointer))")
            builder.unindent()
            builder.appendLine("}")
            builder.unindent()
            builder.appendLine("override fun set$setter(value: $type) {")
            builder.indent()
            builder.appendLine("handle.type = WGPUNativeDisplayHandleType_$setter.toInt()")
            builder.appendLine("handle.data.setType($targetPackage.android.$type.ByValue::class.java)")
            builder.appendLine("handle.data.$field = $targetPackage.android.$type.ByValue(value.handler)")
            builder.appendLine("handle.data.write()")
            builder.appendLine("handle.write()")
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

    private fun mapKmpFunctionType(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED -> mapKmpType(type)
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> {
            val pointee = type.type()
            when {
                pointee is Type.Primitive && pointee.kind() == Type.Primitive.Kind.Char -> "CString?"
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
        type is Type.Declared -> {
            val tree = type.tree()
            val name = tree.name()
            if (name.isNotEmpty() && !name.contains("unnamed")) name else "NativeAddress"
        }
        else -> "NativeAddress"
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

    private fun String.toPublicHandleName(): String =
        if (startsWith("WGPU") && endsWith("Impl")) removeSuffix("Impl") else this

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

    private fun Type.isGeneratedReferenceTypedef(): Boolean {
        val name = referenceTypeName()
        return name != null && name.startsWith("WGPU") && (isReferenceTypedef() || name in generatedStructNames)
    }

    private fun isEnumType(type: Type): Boolean = isEnumCheck(type)

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

    private fun canonicalType(type: Type): Type = when {
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> canonicalType(type.type())
        else -> type
    }

    private fun isEnumCheck(type: Type): Boolean = when {
        type.isEnum() -> true
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> isEnumCheck(type.type())
        else -> false
    }

    private fun canonicalKmpType(type: Type): String {
        val canonical = canonicalType(type)
        return when {
            canonical is Type.Primitive -> mapPrimitive(canonical.kind())
            isEnumCheck(canonical) -> "UInt"
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

    private fun isOptionsStyle(name: String): Boolean =
        name.endsWith("Options") || name.endsWith("Flags") || name.endsWith("Mask") || name == "WGPUInstanceBackend" || name == "WGPUInstanceFlag" || name == "WGPUFlags"

    private fun mapJnaType(type: Type): String = when {
        isEnumType(type) -> "Int"
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
                isEnumType(inner) -> "Int"
                isStructType(inner) -> {
                    val name = type.name()
                    if (name != null && !name.contains("unnamed") && name != "WGPUNativeDisplayHandle") "$targetPackage.android.$name.ByReference?" else "Pointer?"
                }
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
                val name = tree.name()
                if (name != null && !name.contains("unnamed") && name != "WGPUNativeDisplayHandle") "$targetPackage.android.$name.ByReference?" else "Pointer?"
            } else if (tree.kind() == Declaration.Scoped.Kind.ENUM) {
                "Int"
            } else {
                "Pointer?"
            }
        }
        else -> "Pointer?"
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
        return when (jnaType) {
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
