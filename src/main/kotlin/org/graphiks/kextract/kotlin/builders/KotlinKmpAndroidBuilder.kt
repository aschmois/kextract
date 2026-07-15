@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.ClangUnnamedRecord
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.KotlinKmpNamePlan
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.ARRAY_HOLDER
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.C_STRING
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.JNA_POINTER
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.JNA_STRUCTURE
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.JNA_UNION
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.JVM_FIELD
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.MEMORY_ALLOCATOR
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.NATIVE_ADDRESS
import org.graphiks.kextract.kotlin.KotlinKmpSourceSet
import org.graphiks.kextract.kotlin.callbacks.KotlinCallbackBindingEmitter
import org.graphiks.kextract.kotlin.callbacks.KotlinCallbackAndroidEmitter
import org.graphiks.kextract.kotlin.callbacks.KotlinCallbackModel
import org.graphiks.kextract.kotlin.callbacks.KotlinDirectFunctionBindingModel
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.pipeline.LayoutUtils
import org.graphiks.kextract.kotlin.utils.TypeMapper
import org.graphiks.kextract.pipeline.isEnum

internal class KotlinKmpAndroidBuilder(
    private val targetPackage: String,
    private val className: String,
    private val callbackModels: List<KotlinCallbackModel>,
    private val directBindingModels: List<KotlinDirectFunctionBindingModel>,
    private val namePlan: KotlinKmpNamePlan,
) : Declaration.Visitor<Unit> {

    private val builder = SourceBuilder()
    private val jnaBuilder = SourceBuilder()
    private val files = mutableListOf<KotlinSourceFile>()
    private val generatedNames = mutableSetOf<String>()
    private val callbackTypeNames = callbackModels.mapTo(mutableSetOf(), KotlinCallbackModel::typeName)
    private val androidPackage = if (targetPackage.isEmpty()) "android" else "$targetPackage.android"
    private val typeMapper = KmpTypeMapper(namePlan, arraysAsHolders = false)
    private val nativeAddress = namePlan.runtime(NATIVE_ADDRESS)
    private val cString = namePlan.runtime(C_STRING)
    private val arrayHolder = namePlan.runtime(ARRAY_HOLDER)
    private val memoryAllocator = namePlan.runtime(MEMORY_ALLOCATOR)
    private val jnaPointer = namePlan.runtime(JNA_POINTER)
    private val jnaStructure = namePlan.runtime(JNA_STRUCTURE)
    private val jnaUnion = namePlan.runtime(JNA_UNION)
    private val jvmField = namePlan.runtime(JVM_FIELD)

    init {
        if (targetPackage.isNotEmpty()) {
            builder.appendLine("package $targetPackage")
            builder.appendLine()
        }
        jnaBuilder.appendLine("package $androidPackage")
        jnaBuilder.appendLine()

        KotlinKmpRuntimeSymbol.entries
            .filter { KotlinKmpSourceSet.ANDROID in it.sourceSets }
            .filterNot { it in setOf(JNA_POINTER, JNA_STRUCTURE, JNA_UNION, JVM_FIELD) }
            .forEach { builder.appendLine(namePlan.importLine(it)) }
        builder.appendLine()

        listOf(JNA_POINTER, JNA_STRUCTURE, JNA_UNION, JVM_FIELD)
            .forEach { jnaBuilder.appendLine(namePlan.importLine(it)) }
        jnaBuilder.appendLine()
    }

    override fun visitScoped(decl: Declaration.Scoped) {
        if (Skip.isPresent(decl)) return
        when (decl.kind()) {
            Declaration.Scoped.Kind.STRUCT,
            Declaration.Scoped.Kind.UNION -> {
                val structName = namePlan.declaration(decl)
                if (structName.isEmpty() || structName.contains("unnamed")) return
                if (structName.endsWith("Impl") && decl.members().isEmpty()) return
                if (!generatedNames.add(structName)) return
                if (structName == "WGPUNativeDisplayHandle") {
                    emitNativeDisplayHandle(decl)
                    return
                }

                val fields = decl.members().filterIsInstance<Declaration.Variable>().filterNot(Skip::isPresent)
                val jnaByReference = namePlan.jnaByReference(decl)
                val jnaByValue = namePlan.jnaByValue(decl)
                val rawJnaByReference = "$androidPackage.$structName.$jnaByReference"
                val rawJnaByValue = "$androidPackage.$structName.$jnaByValue"

                // 1. Generate the Bridge Actual Interface
                builder.appendLine("actual interface $structName {")
                builder.indent()

                fields.forEach { field ->
                    val fieldName = namePlan.member(field)
                    val fieldType = typeMapper.mapType(field.type())
                    if (fieldType == cString) {
                        builder.appendLine("actual var $fieldName: $cString?")
                    } else if (fieldType.startsWith(arrayHolder)) {
                        builder.appendLine("actual var $fieldName: $fieldType?")
                    } else if (fieldType.endsWith("?") || fieldType == nativeAddress) {
                        builder.appendLine("actual var $fieldName: $fieldType")
                    } else {
                        builder.appendLine("actual var $fieldName: $fieldType")
                    }
                }
                builder.appendLine("actual val handler: $nativeAddress")

                // Companion object
                builder.appendLine("actual companion object {")
                builder.indent()
                builder.appendLine("actual operator fun invoke(address: $nativeAddress): $structName {")
                builder.indent()
                builder.appendLine("return ByReference($rawJnaByReference(address))")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine()
                builder.appendLine("actual fun allocate(allocator: $memoryAllocator): $structName {")
                builder.indent()
                builder.appendLine("val ref = $rawJnaByReference()")
                builder.appendLine("allocator.register(ref)")
                builder.appendLine("return ByReference(ref)")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine()
                builder.appendLine("actual fun allocateArray(allocator: $memoryAllocator, size: UInt, provider: (UInt, $structName) -> Unit): $arrayHolder<$structName> {")
                builder.indent()
                builder.appendLine("val ref = $rawJnaByValue()")
                builder.appendLine("val array = ref.toArray(size.toInt())")
                builder.appendLine("array.forEachIndexed { index, struct ->")
                builder.indent()
                builder.appendLine("provider(index.toUInt(), ByValue(struct as $rawJnaByValue))")
                builder.unindent()
                builder.appendLine("}")
                builder.appendLine("val pointer = if (size == 0u) com.sun.jna.Pointer.NULL else array.first().pointer")
                builder.appendLine("return $arrayHolder(pointer)")
                builder.unindent()
                builder.appendLine("}")
                builder.unindent()
                builder.appendLine("}") // End companion object

                // Generate ByReference Implementation
                builder.appendLine()
                builder.appendLine("class ByReference(val handle: $rawJnaByReference = $rawJnaByReference(com.sun.jna.Pointer.NULL)) : $structName {")
                builder.indent()
                fields.forEach { field ->
                    val fieldName = namePlan.rawIdentifier(field)
                    val propertyName = namePlan.member(field)
                    val fieldType = typeMapper.mapType(field.type())
                    if (fieldType == cString) {
                        builder.appendLine("override var $propertyName: $cString?")
                    } else if (fieldType.startsWith(arrayHolder)) {
                        builder.appendLine("override var $propertyName: $fieldType?")
                    } else if (fieldType.endsWith("?") || fieldType == nativeAddress) {
                        builder.appendLine("override var $propertyName: $fieldType")
                    } else {
                        builder.appendLine("override var $propertyName: $fieldType")
                    }
                    val inlineJnaType = inlineRecordJnaType(field.type())
                    if (decl.kind() == Declaration.Scoped.Kind.UNION) {
                        builder.indent()
                        emitUnionFieldAccessors(field)
                        builder.unindent()
                    } else if (inlineJnaType != null) {
                        builder.indent()
                        emitInlineRecordAccessors(field, fieldType)
                        builder.unindent()
                    } else if (isStructType(field.type())) {
                        builder.indent()
                        val isOpt = fieldType == cString || fieldType.startsWith(arrayHolder) || fieldType.endsWith("?")
                        val nonOpt = fieldType.removeSuffix("?")
                        val jnaType = mapJnaType(field.type())
                        val rawFieldJnaByReference = requireNotNull(jnaByReferenceType(field.type()))
                        if (fieldType.startsWith(arrayHolder)) {
                            builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference(it) }")
                            builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle }")
                        } else if (nonOpt == "WGPUNativeDisplayHandle") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($rawFieldJnaByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? WGPUNativeDisplayHandle.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($rawFieldJnaByReference(it)) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as WGPUNativeDisplayHandle.ByReference).handle.pointer }")
                            }
                        } else if (jnaType == "$jnaPointer?") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference($rawFieldJnaByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { $fieldType.ByReference($rawFieldJnaByReference(it)) } ?: error(\"$fieldName is null\")")
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
                            fieldType == cString -> {
                                builder.appendLine("get() = handle.$fieldName?.let(::$cString)")
                                builder.appendLine("set(value) { handle.$fieldName = value?.handler }")
                            }
                            fieldType == nativeAddress -> {
                                builder.appendLine("get() = handle.$fieldName ?: com.sun.jna.Pointer.NULL")
                                builder.appendLine("set(value) { handle.$fieldName = value }")
                            }
                            fieldType == "$nativeAddress?" -> {
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
                                val isPrimitiveOrKffi = fieldType in listOf("Byte", "Short", "Int", "Long", "Float", "Double", "Boolean", cString, nativeAddress)
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
                builder.appendLine("override val handler: $nativeAddress")
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
                builder.appendLine("class ByValue(val handle: $rawJnaByValue = $rawJnaByValue(com.sun.jna.Pointer.NULL)) : $structName {")
                builder.indent()
                fields.forEach { field ->
                    val fieldName = namePlan.rawIdentifier(field)
                    val propertyName = namePlan.member(field)
                    val fieldType = typeMapper.mapType(field.type())
                    if (fieldType == cString) {
                        builder.appendLine("override var $propertyName: $cString?")
                    } else if (fieldType.startsWith(arrayHolder)) {
                        builder.appendLine("override var $propertyName: $fieldType?")
                    } else if (fieldType.endsWith("?") || fieldType == nativeAddress) {
                        builder.appendLine("override var $propertyName: $fieldType")
                    } else {
                        builder.appendLine("override var $propertyName: $fieldType")
                    }
                    val inlineJnaType = inlineRecordJnaType(field.type())
                    if (decl.kind() == Declaration.Scoped.Kind.UNION) {
                        builder.indent()
                        emitUnionFieldAccessors(field)
                        builder.unindent()
                    } else if (inlineJnaType != null) {
                        builder.indent()
                        emitInlineRecordAccessors(field, fieldType)
                        builder.unindent()
                    } else if (isStructType(field.type())) {
                        builder.indent()
                        val isOpt = fieldType == cString || fieldType.startsWith(arrayHolder) || fieldType.endsWith("?")
                        val nonOpt = fieldType.removeSuffix("?")
                        val jnaType = mapJnaType(field.type())
                        val rawFieldJnaByReference = requireNotNull(jnaByReferenceType(field.type()))
                        if (fieldType.startsWith(arrayHolder)) {
                            builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference(it) }")
                            builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle }")
                        } else if (nonOpt == "WGPUNativeDisplayHandle") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($rawFieldJnaByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? WGPUNativeDisplayHandle.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { WGPUNativeDisplayHandle.ByReference($rawFieldJnaByReference(it)) } ?: error(\"$fieldName is null\")")
                                builder.appendLine("set(value) { handle.$fieldName = (value as WGPUNativeDisplayHandle.ByReference).handle.pointer }")
                            }
                        } else if (jnaType == "$jnaPointer?") {
                            if (isOpt) {
                                builder.appendLine("get() = handle.$fieldName?.let { $nonOpt.ByReference($rawFieldJnaByReference(it)) }")
                                builder.appendLine("set(value) { handle.$fieldName = (value as? $nonOpt.ByReference)?.handle?.pointer }")
                            } else {
                                builder.appendLine("get() = handle.$fieldName?.let { $fieldType.ByReference($rawFieldJnaByReference(it)) } ?: error(\"$fieldName is null\")")
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
                            fieldType == cString -> {
                                builder.appendLine("get() = handle.$fieldName?.let(::$cString)")
                                builder.appendLine("set(value) { handle.$fieldName = value?.handler }")
                            }
                            fieldType == nativeAddress -> {
                                builder.appendLine("get() = handle.$fieldName ?: com.sun.jna.Pointer.NULL")
                                builder.appendLine("set(value) { handle.$fieldName = value }")
                            }
                            fieldType == "$nativeAddress?" -> {
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
                                val isPrimitiveOrKffi = fieldType in listOf("Byte", "Short", "Int", "Long", "Float", "Double", "Boolean", cString, nativeAddress)
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
                builder.appendLine("override val handler: $nativeAddress")
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
                val jnaBase = if (decl.kind() == Declaration.Scoped.Kind.UNION) jnaUnion else jnaStructure
                jnaBuilder.appendLine("open class $structName(pointer: $jnaPointer? = null) : $jnaBase(pointer) {")
                jnaBuilder.indent()
                fields.forEach { field ->
                    val fieldName = namePlan.rawIdentifier(field)
                    val fieldType = mapJnaType(field.type())
                    jnaBuilder.appendLine("@$jvmField var $fieldName: ${fieldType} = ${getDefaultJnaValue(field.type())}")
                }
                jnaBuilder.appendLine("override fun getFieldOrder() = listOf<String>(${fields.joinToString(", ") { "\"${it.name()}\"" }})")

                jnaBuilder.appendLine("class $jnaByReference(pointer: $jnaPointer? = null) : $structName(pointer), $jnaStructure.ByReference")
                jnaBuilder.appendLine("class $jnaByValue(pointer: $jnaPointer? = null) : $structName(pointer), $jnaStructure.ByValue")
                jnaBuilder.unindent()
                jnaBuilder.appendLine("}")
                jnaBuilder.appendLine()
            }
            Declaration.Scoped.Kind.TOPLEVEL -> {
                for (member in decl.members()) {
                    member.accept(this)
                }
                KotlinCallbackAndroidEmitter(namePlan).emit(builder, callbackModels)
                KotlinCallbackBindingEmitter(typeMapper::mapFunctionType, namePlan).emitAndroid(
                    builder,
                    directBindingModels,
                )
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
        val returnType = typeMapper.mapFunctionType(decl.type().returnType())
        val params = decl.parameters().map { param ->
            val name = namePlan.parameter(param)
            "$name: ${typeMapper.mapFunctionType(param.type())}"
        }.joinToString(", ")
        builder.appendLine("actual fun ${namePlan.declaration(decl)}($params): $returnType =")
        builder.indent()
        builder.appendLine("error(\"${decl.name()} is not implemented for Android/JNA generated bindings\")")
        builder.unindent()
        builder.appendLine()
    }
    override fun visitVariable(decl: Declaration.Variable) {}
    override fun visitTypedef(decl: Declaration.Typedef) {
        if (Skip.isPresent(decl)) return
        val name = namePlan.declaration(decl)
        if (name.isEmpty()) return
        if (name in callbackTypeNames || typeMapper.callbackFunction(decl.type()) != null) return
        val inner = decl.type()
        if (inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER) {
            val pointee = inner.type()
            if (pointee is Type.Declared && pointee.tree().kind() == Declaration.Scoped.Kind.STRUCT) {
                val pointeeName = pointee.tree().name()
                if (pointeeName.isNotEmpty() && pointeeName.endsWith("Impl")) {
                    if (!generatedNames.add(name)) return
                    builder.appendLine("@kotlin.jvm.JvmInline")
                    builder.appendLine("actual value class $name actual constructor(actual val handler: $nativeAddress)")
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

    private fun emitNativeDisplayHandle(decl: Declaration.Scoped) {
        val rawJnaByReference =
            "$androidPackage.WGPUNativeDisplayHandle.${namePlan.jnaByReference(decl)}"
        val rawJnaByValue = "$androidPackage.WGPUNativeDisplayHandle.${namePlan.jnaByValue(decl)}"
        builder.appendLine("actual interface WGPUNativeDisplayHandle {")
        builder.indent()
        builder.appendLine("actual var type: WGPUNativeDisplayHandleType")
        builder.appendLine("actual val xlib: WGPUXlibDisplayHandle?")
        builder.appendLine("actual fun setXlib(value: WGPUXlibDisplayHandle)")
        builder.appendLine("actual val xcb: WGPUXcbDisplayHandle?")
        builder.appendLine("actual fun setXcb(value: WGPUXcbDisplayHandle)")
        builder.appendLine("actual val wayland: WGPUWaylandDisplayHandle?")
        builder.appendLine("actual fun setWayland(value: WGPUWaylandDisplayHandle)")
        builder.appendLine("actual val handler: $nativeAddress")
        builder.appendLine("actual companion object {")
        builder.indent()
        builder.appendLine("actual operator fun invoke(address: $nativeAddress): WGPUNativeDisplayHandle = ByReference($rawJnaByReference(address))")
        builder.appendLine("actual fun allocate(allocator: $memoryAllocator): WGPUNativeDisplayHandle {")
        builder.indent()
        builder.appendLine("val ref = $rawJnaByReference()")
        builder.appendLine("allocator.register(ref)")
        builder.appendLine("return ByReference(ref)")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("actual fun allocateArray(allocator: $memoryAllocator, size: UInt, provider: (UInt, WGPUNativeDisplayHandle) -> Unit): $arrayHolder<WGPUNativeDisplayHandle> {")
        builder.indent()
        builder.appendLine("val ref = $rawJnaByValue()")
        builder.appendLine("val array = ref.toArray(size.toInt())")
        builder.appendLine("array.forEachIndexed { index, struct -> provider(index.toUInt(), ByValue(struct as $rawJnaByValue)) }")
        builder.appendLine("val pointer = if (size == 0u) com.sun.jna.Pointer.NULL else array.first().pointer")
        builder.appendLine("return $arrayHolder(pointer)")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        emitNativeDisplayHandleAndroidImpl("ByReference", rawJnaByReference)
        emitNativeDisplayHandleAndroidImpl("ByValue", rawJnaByValue)
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()

        jnaBuilder.appendLine("open class WGPUNativeDisplayHandle(pointer: $jnaPointer? = null) : $jnaStructure(pointer) {")
        jnaBuilder.indent()
        jnaBuilder.appendLine("@$jvmField var type: Int = 0")
        jnaBuilder.appendLine("@$jvmField var data: Data = Data()")
        jnaBuilder.appendLine("override fun getFieldOrder() = listOf<String>(\"type\", \"data\")")
        jnaBuilder.appendLine("class Data : com.sun.jna.Union(), $jnaStructure.ByValue {")
        jnaBuilder.indent()
        listOf("WGPUXlibDisplayHandle", "WGPUXcbDisplayHandle", "WGPUWaylandDisplayHandle").forEach { type ->
            val field = type.removePrefix("WGPU").removeSuffix("DisplayHandle").lowercase()
            val helper = "$type.${namePlan.jnaByValue(type)}"
            jnaBuilder.appendLine("@$jvmField var $field: $helper = $helper()")
        }
        jnaBuilder.unindent()
        jnaBuilder.appendLine("}")
        jnaBuilder.appendLine("class ${namePlan.jnaByReference(decl)}(pointer: $jnaPointer? = null) : WGPUNativeDisplayHandle(pointer), $jnaStructure.ByReference")
        jnaBuilder.appendLine("class ${namePlan.jnaByValue(decl)}(pointer: $jnaPointer? = null) : WGPUNativeDisplayHandle(pointer), $jnaStructure.ByValue")
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
            builder.appendLine("val copy = $androidPackage.$type.${namePlan.jnaByValue(type)}(value.handler)")
            builder.appendLine("copy.read()")
            builder.appendLine("handle.data.$field = copy")
            builder.appendLine("handle.data.writeField(\"$field\")")
            builder.appendLine("handle.writeField(\"type\")")
            builder.appendLine("handle.writeField(\"data\")")
            builder.unindent()
            builder.appendLine("}")
        }
        builder.appendLine("override val handler: $nativeAddress")
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
                    tree.members().filterIsInstance<Declaration.Variable>().isNotEmpty() &&
                    !ClangUnnamedRecord.isPresent(tree)
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
        inlineRecordDeclaration(type)
            ?.takeIf { record -> record.name().isNotEmpty() && !record.name().contains("unnamed") }
            ?.let { record ->
                "$androidPackage.${namePlan.declaration(record)}.${namePlan.jnaByValue(record)}"
            }

    private fun inlineRecordDeclaration(type: Type): Declaration.Scoped? = when {
        type is Type.Declared && isStructType(type) -> type.tree()
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> inlineRecordDeclaration(type.type())
        else -> null
    }

    private fun canonicalRecordDeclaration(type: Type): Declaration.Scoped? = when (type) {
        is Type.Declared -> type.tree().takeIf { record ->
            record.kind() == Declaration.Scoped.Kind.STRUCT || record.kind() == Declaration.Scoped.Kind.UNION
        }
        is Type.Delegated -> canonicalRecordDeclaration(type.type())
        is Type.Array -> canonicalRecordDeclaration(type.elementType())
        else -> null
    }

    private fun jnaByReferenceType(type: Type): String? = canonicalRecordDeclaration(type)?.let { record ->
        "$androidPackage.${namePlan.declaration(record)}.${namePlan.jnaByReference(record)}"
    }

    private fun emitInlineRecordAccessors(field: Declaration.Variable, fieldType: String) {
        val cFieldName = field.name()
        val fieldName = namePlan.rawIdentifier(field)
        builder.appendLine("get() {")
        builder.indent()
        builder.appendLine("handle.readField(\"$cFieldName\")")
        builder.appendLine("return $fieldType.ByValue(handle.$fieldName)")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("set(value) {")
        builder.indent()
        builder.appendLine("val bytes = value.handler.getByteArray(0, handle.$fieldName.size())")
        builder.appendLine("handle.readField(\"$cFieldName\")")
        builder.appendLine("handle.$fieldName.pointer.write(0, bytes, 0, bytes.size)")
        builder.appendLine("handle.readField(\"$cFieldName\")")
        builder.unindent()
        builder.appendLine("}")
    }

    private fun emitUnionFieldAccessors(field: Declaration.Variable) {
        val cFieldName = field.name()
        val fieldName = namePlan.rawIdentifier(field)
        val fieldType = typeMapper.mapType(field.type())
        val inlineJnaType = inlineRecordJnaType(field.type())
        if (inlineJnaType != null) {
            emitInlineRecordAccessors(field, fieldType)
            return
        }

        val getter = when {
            fieldType == cString -> "handle.$fieldName?.let(::$cString)"
            fieldType == nativeAddress -> "handle.$fieldName ?: com.sun.jna.Pointer.NULL"
            fieldType == "$nativeAddress?" -> "handle.$fieldName"
            fieldType == "Boolean" -> "handle.$fieldName != 0"
            typeMapper.isEnumType(field.type()) || fieldType == "UInt" ->
                "handle.$fieldName.toUInt() as $fieldType"
            fieldType == "ULong" || fieldType.endsWith("Flags") || fieldType.endsWith("Usage") ->
                "handle.$fieldName.toULong() as $fieldType"
            fieldType == "UShort" -> "handle.$fieldName.toUShort() as $fieldType"
            fieldType == "UByte" -> "handle.$fieldName.toUByte() as $fieldType"
            fieldType.startsWith(arrayHolder) -> "handle.$fieldName?.let { $arrayHolder(it) }"
            fieldType !in listOf("Byte", "Short", "Int", "Long", "Float", "Double") && fieldType.endsWith("?") -> {
                val nonOpt = fieldType.removeSuffix("?")
                "handle.$fieldName?.let { $nonOpt(it) }"
            }
            fieldType !in listOf("Byte", "Short", "Int", "Long", "Float", "Double") ->
                "handle.$fieldName?.let { $fieldType(it) } ?: error(\"$fieldName is null\")"
            else -> "handle.$fieldName as $fieldType"
        }
        val assignment = when {
            fieldType == nativeAddress || fieldType == "$nativeAddress?" ->
                "handle.$fieldName = value"
            fieldType == "Boolean" -> "handle.$fieldName = if (value) 1 else 0"
            fieldType == cString || fieldType.startsWith(arrayHolder) || fieldType.endsWith("?") ->
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
        builder.appendLine("handle.readField(\"$cFieldName\")")
        builder.appendLine("return $getter")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("set(value) {")
        builder.indent()
        builder.appendLine(assignment)
        builder.appendLine("handle.writeField(\"$cFieldName\")")
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
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> "$jnaPointer?"
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> {
            val inner = type.type()
            when {
                typeMapper.isEnumType(inner) -> "Int"
                isStructType(inner) -> "$jnaPointer?"
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
                else -> "$jnaPointer?"
            }
        }
        type is Type.Declared -> {
            val tree = type.tree()
            if (tree.kind() == Declaration.Scoped.Kind.STRUCT || tree.kind() == Declaration.Scoped.Kind.UNION) {
                "$jnaPointer?"
            } else if (tree.kind() == Declaration.Scoped.Kind.ENUM) {
                "Int"
            } else {
                "$jnaPointer?"
            }
        }
        else -> "$jnaPointer?"
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
        else -> "$jnaPointer?"
    }

    private fun getDefaultJnaValue(type: Type): String {
        inlineRecordJnaType(type)?.let { return "$it()" }
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
