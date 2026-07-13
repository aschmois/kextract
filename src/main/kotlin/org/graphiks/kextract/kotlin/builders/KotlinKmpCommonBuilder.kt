@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.models.KotlinSourceFile

class KotlinKmpCommonBuilder(
    private val targetPackage: String,
    private val className: String
) : Declaration.Visitor<Unit> {

    private val builder = SourceBuilder()
    private val files = mutableListOf<KotlinSourceFile>()
    private val generatedNames = mutableSetOf<String>()
    private val generatedStructNames = mutableSetOf<String>()
    private val callbackFunctions = mutableMapOf<String, Type.Function>()
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
        builder.appendLine()
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
                    emitNativeDisplayHandle(decl)
                    return
                }

                builder.appendLine("expect interface $structName {")
                builder.indent()

                // Visit members
                decl.members().filterIsInstance<Declaration.Variable>().filterNot(Skip::isPresent).forEach { field ->
                    val fieldName = field.name()
                    val fieldType = mapKmpType(field.type())
                    if (fieldType == "CString") {
                        builder.appendLine("var $fieldName: CString?")
                    } else if (fieldType.startsWith("ArrayHolder")) {
                        builder.appendLine("var $fieldName: $fieldType?")
                    } else if (fieldType.endsWith("?") || fieldType == "NativeAddress") {
                        builder.appendLine("var $fieldName: $fieldType")
                    } else {
                        builder.appendLine("var $fieldName: $fieldType")
                    }
                }

                builder.appendLine("val handler: NativeAddress")

                // Companion object
                builder.appendLine("companion object {")
                builder.indent()
                builder.appendLine("operator fun invoke(address: NativeAddress): $structName")
                builder.appendLine("fun allocate(allocator: MemoryAllocator): $structName")
                builder.appendLine("fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, $structName) -> Unit): ArrayHolder<$structName>")
                builder.unindent()
                builder.appendLine("}")

                builder.unindent()
                builder.appendLine("}")
                builder.appendLine()
            }
            Declaration.Scoped.Kind.ENUM -> {
                val name = decl.name()
                if (name.isNotEmpty() && !name.contains("unnamed")) {
                    if (!generatedNames.add(name)) return
                    val constants = decl.members().filterIsInstance<Declaration.Constant>().filterNot(Skip::isPresent)
                    if (isOptionsStyle(name)) {
                        emitValueClass(name, constants)
                    } else {
                        emitEnumClass(name, constants)
                    }
                }
            }
            Declaration.Scoped.Kind.TOPLEVEL -> {
                emitFlagTypedefs(decl)
                for (member in decl.members()) {
                    member.accept(this)
                }
            }
            else -> {}
        }

        if (decl.kind() == Declaration.Scoped.Kind.TOPLEVEL) {
            files.add(KotlinSourceFile(targetPackage, className + "Common", builder.toString()))
        }
    }

    private fun emitEnumClass(name: String, constants: List<Declaration.Constant>) {
        builder.appendLine("typealias ${name} = UInt")
        for (c in constants) {
            builder.appendLine("const val ${c.name()} : ${name} = ${c.value().toLongValue()}u")
        }
        builder.appendLine()
    }

    private fun emitValueClass(name: String, constants: List<Declaration.Constant>) {
        builder.appendLine("@kotlin.jvm.JvmInline")
        builder.appendLine("value class ${name}(val rawValue: Long) {")
        builder.indent()

        if (constants.isNotEmpty()) {
            builder.appendLine("companion object {")
            builder.indent()
            for (c in constants) {
                builder.appendLine("val ${c.name()} = ${name}(${c.value().toLongValue().toKotlinLongLiteral()})")
            }
            builder.unindent()
            builder.appendLine("}")
            builder.appendLine()
        }

        builder.appendLine("operator fun plus(o: ${name}) = ${name}(rawValue or o.rawValue)")
        builder.appendLine("operator fun contains(o: ${name}) = (rawValue and o.rawValue) != 0L")

        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun isOptionsStyle(name: String): Boolean =
        name.endsWith("Options") || name.endsWith("Flags") || name.endsWith("Mask")

    private fun emitFlagTypedefs(decl: Declaration.Scoped) {
        val typedefs = decl.members()
            .filterIsInstance<Declaration.Typedef>()
            .filterNot(Skip::isPresent)
        val constants = decl.members().filterIsInstance<Declaration.Constant>().filterNot(Skip::isPresent)
        val flagTypedefs = typedefs
            .filter { typedef ->
                typedef.name().startsWith("WGPU") &&
                    typedef.name() != "WGPUFlags" &&
                    constants.any { it.name().startsWith("${typedef.name()}_") }
            }

        flagTypedefs.forEach { typedef ->
            val flagName = typedef.name()
            if (!generatedNames.add(flagName)) return@forEach

            builder.appendLine("typealias $flagName = ULong")
            constants
                .filter { it.name().startsWith("${flagName}_") }
                .forEach { constant ->
                    builder.appendLine("const val ${constant.name()} : $flagName = ${constant.value().toLongValue().toKotlinULongLiteral()}")
                }
            builder.appendLine()
        }
    }

    private fun emitNativeDisplayHandle(decl: Declaration.Scoped) {
        val unionField = inlineUnionField(decl)
        val fields = decl.members()
            .filterIsInstance<Declaration.Variable>()
            .filterNot { it == unionField }
        val unionFields = nativeDisplayUnionFields(decl)

        builder.appendLine("expect interface WGPUNativeDisplayHandle {")
        builder.indent()

        fields.forEach { field ->
            builder.appendLine("var ${field.name()}: ${mapKmpType(field.type())}")
        }
        unionFields.forEach { field ->
            val type = mapKmpType(field.type())
            val setter = field.name().replaceFirstChar { it.titlecase() }
            builder.appendLine("val ${field.name()}: $type?")
            builder.appendLine("fun set$setter(value: $type)")
        }

        builder.appendLine("val handler: NativeAddress")
        builder.appendLine("companion object {")
        builder.indent()
        builder.appendLine("operator fun invoke(address: NativeAddress): WGPUNativeDisplayHandle")
        builder.appendLine("fun allocate(allocator: MemoryAllocator): WGPUNativeDisplayHandle")
        builder.appendLine("fun allocateArray(allocator: MemoryAllocator, size: UInt, provider: (UInt, WGPUNativeDisplayHandle) -> Unit): ArrayHolder<WGPUNativeDisplayHandle>")
        builder.unindent()
        builder.appendLine("}")

        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun nativeDisplayUnionFields(decl: Declaration.Scoped): List<Declaration.Variable> =
        inlineUnionField(decl)?.type()?.declaredUnion()?.members()?.filterIsInstance<Declaration.Variable>()
            ?: decl.members()
                .filterIsInstance<Declaration.Scoped>()
                .firstOrNull { it.kind() == Declaration.Scoped.Kind.UNION }
                ?.members()
                ?.filterIsInstance<Declaration.Variable>()
            ?: emptyList()

    private fun inlineUnionField(decl: Declaration.Scoped): Declaration.Variable? =
        decl.members()
            .filterIsInstance<Declaration.Variable>()
            .firstOrNull { it.type().declaredUnion() != null }

    private fun Type.declaredUnion(): Declaration.Scoped? = when (this) {
        is Type.Declared -> tree().takeIf { it.kind() == Declaration.Scoped.Kind.UNION }
        is Type.Delegated -> type().declaredUnion()
        else -> null
    }

    private fun Any.toLongValue(): Long = when (this) {
        is Long -> this
        is Int  -> this.toLong()
        else    -> toString().toLongOrNull() ?: 0L
    }

    private fun Long.toKotlinLongLiteral(): String =
        if (this == Long.MIN_VALUE) "Long.MIN_VALUE" else "${this}L"

    private fun Long.toKotlinULongLiteral(): String =
        "${java.lang.Long.toUnsignedString(this)}uL"

    override fun visitFunction(decl: Declaration.Function) {
        if (Skip.isPresent(decl)) return
        if (!decl.name().startsWith("wgpu")) return
        val returnType = mapKmpFunctionType(decl.type().returnType())
        val params = decl.parameters().mapIndexed { index, param ->
            val name = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            "$name: ${mapKmpFunctionType(param.type())}"
        }.joinToString(", ")
        builder.appendLine("expect fun ${decl.name()}($params): $returnType")
        builder.appendLine()
        emitCallbackConvenienceOverload(decl)
    }
    override fun visitVariable(decl: Declaration.Variable) {}
    override fun visitTypedef(decl: Declaration.Typedef) {
        if (Skip.isPresent(decl)) return
        val name = decl.name()
        if (name.isEmpty() || !name.startsWith("WGPU")) return
        if (name.endsWith("Callback")) decl.type().callbackFunction()?.let { function ->
            if (!generatedNames.add(name)) return
            callbackFunctions[name] = function
            emitCallbackExpect(name, function)
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
                    builder.appendLine("expect value class $name(val handler: NativeAddress)")
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

    private fun emitCallbackExpect(name: String, function: Type.Function) {
        builder.appendLine("expect class $name : AutoCloseable {")
        builder.indent()
        builder.appendLine("val handler: NativeAddress")
        builder.appendLine("override fun close()")
        builder.appendLine("companion object {")
        builder.indent()
        builder.appendLine("fun allocate(callback: ${callbackLambdaType(function)}): $name")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitCallbackConvenienceOverload(decl: Declaration.Function) {
        if (mapKmpFunctionType(decl.type().returnType()) != "Unit") return
        val callbackParam = decl.parameters().firstOrNull { it.type().callbackFunction() != null } ?: return
        val callbackName = callbackParam.type().callbackTypeName() ?: return
        if (!callbackName.endsWith("Callback")) return
        val callbackFunction = callbackFunctions[callbackName] ?: callbackParam.type().callbackFunction() ?: return
        val valueParams = decl.parameters().filter { it !== callbackParam }
        val helperParams = valueParams.mapIndexed { index, param ->
            val paramName = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            val paramType = mapKmpFunctionType(param.type())
            val default = if (paramName == "userdata" && paramType == "NativeAddress?") " = null" else ""
            "$paramName: $paramType$default"
        } + "callback: ${callbackLambdaType(callbackFunction)}"
        val callArgs = decl.parameters().mapIndexed { index, param ->
            val paramName = param.name().takeIf { it.isNotEmpty() } ?: "arg$index"
            if (param === callbackParam) "holder" else paramName
        }.joinToString(", ")

        builder.appendLine("fun ${decl.name()}(${helperParams.joinToString(", ")}): $callbackName {")
        builder.indent()
        builder.appendLine("val holder = $callbackName.allocate(callback)")
        builder.appendLine("${decl.name()}($callArgs)")
        builder.appendLine("return holder")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun callbackLambdaType(function: Type.Function): String {
        val names = function.parameterNames().orEmpty()
        val params = function.argumentTypes().mapIndexed { index, type ->
            val name = names.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "arg$index"
            "$name: ${mapKmpFunctionType(type)}"
        }.joinToString(", ")
        return "($params) -> ${mapKmpFunctionType(function.returnType())}"
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
                pointee is Type.Function -> "NativeAddress?"
                pointee is Type.Delegated && pointee.kind() == Type.Delegated.Kind.TYPEDEF && pointee.type() is Type.Function -> "NativeAddress?"
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
        type is Type.Function -> "NativeAddress?"
        type is Type.Declared -> {
            val name = type.tree().name()
            if (name.isNotEmpty() && !name.contains("unnamed")) name else "NativeAddress"
        }
        type is Type.Array -> "ArrayHolder<${mapKmpFunctionType(type.elementType()).removeSuffix("?")}>?"
        else -> "NativeAddress"
    }

    private fun Type.callbackFunction(): Type.Function? = when {
        this is Type.Delegated && kind() == Type.Delegated.Kind.TYPEDEF -> type().callbackFunction()
        this is Type.Delegated && kind() == Type.Delegated.Kind.POINTER -> type().callbackFunction()
        this is Type.Function -> this
        else -> null
    }

    private fun Type.callbackTypeName(): String? = when {
        this is Type.Delegated && kind() == Type.Delegated.Kind.TYPEDEF && callbackFunction() != null -> name()
        else -> null
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
}
