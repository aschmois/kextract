package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type
import org.graphiks.kextract.pipeline.isEnum

internal class KmpTypeMapper(
    private val opaqueHandleAliases: Map<String, String>,
    private val generatedStructNames: Set<String>,
    private val arraysAsHolders: Boolean = true,
) {
    fun mapType(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED -> mapUnsigned(type.type())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> mapPointer(type.type(), charNullable = false)
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> mapTypedef(type)
        type is Type.Declared -> declaredName(type)
        else -> "NativeAddress"
    }

    fun mapFunctionType(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED -> mapType(type)
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> mapFunctionPointer(type.type())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> mapFunctionTypedef(type)
        type is Type.Function -> "NativeAddress?"
        type is Type.Declared -> declaredName(type)
        type is Type.Array && arraysAsHolders -> "ArrayHolder<${mapFunctionType(type.elementType()).removeSuffix("?")}>?"
        else -> "NativeAddress"
    }

    fun mapPrimitive(kind: Type.Primitive.Kind): String = when (kind) {
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

    fun callbackFunction(type: Type): Type.Function? = type.callbackFunctionOrNull()

    fun callbackTypeName(type: Type): String? = when {
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF && callbackFunction(type) != null -> type.name()
        else -> null
    }

    fun callbackLambdaType(function: Type.Function): String {
        val names = function.parameterNames().orEmpty()
        val params = function.argumentTypes().mapIndexed { index, type ->
            val name = names.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "arg$index"
            "$name: ${mapFunctionType(type)}"
        }.joinToString(", ")
        return "($params) -> ${mapFunctionType(function.returnType())}"
    }

    fun declaredUnion(type: Type): Declaration.Scoped? = when (type) {
        is Type.Declared -> type.tree().takeIf { it.kind() == Declaration.Scoped.Kind.UNION }
        is Type.Delegated -> declaredUnion(type.type())
        else -> null
    }

    fun canonicalKmpType(type: Type): String {
        val canonical = canonicalType(type)
        return when {
            canonical is Type.Primitive -> mapPrimitive(canonical.kind())
            isEnumType(canonical) -> "UInt"
            canonical is Type.Delegated && canonical.kind() == Type.Delegated.Kind.UNSIGNED -> mapUnsigned(canonical.type())
            else -> "Other"
        }
    }

    fun isEnumType(type: Type): Boolean = when {
        type.isEnum() -> true
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> isEnumType(type.type())
        else -> false
    }

    fun isInlineStructOrUnion(type: Type): Boolean {
        val fieldType = mapType(type)
        return canonicalKmpType(type) == "Other" &&
            fieldType != "NativeAddress" &&
            fieldType != "CString" &&
            !fieldType.endsWith("?")
    }

    private fun mapUnsigned(inner: Type): String = if (inner is Type.Primitive) {
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

    private fun mapPointer(pointee: Type, charNullable: Boolean): String = when {
        pointee is Type.Primitive && pointee.kind() == Type.Primitive.Kind.Char -> if (charNullable) "CString?" else "CString"
        pointee is Type.Delegated && isGeneratedReferenceTypedef(pointee) -> "${referenceTypeName(pointee)}?"
        pointee is Type.Declared && pointee.tree().kind() in setOf(Declaration.Scoped.Kind.STRUCT, Declaration.Scoped.Kind.UNION) -> {
            val name = pointee.tree().name()
            opaqueHandleAliases[name]?.let { "$it?" }
                ?: name.takeIf { it.startsWith("WGPU") && it.endsWith("Impl") }?.removeSuffix("Impl")?.let { "$it?" }
                ?: if (name.isNotEmpty() && !name.contains("unnamed")) "$name?" else "NativeAddress?"
        }
        else -> "NativeAddress?"
    }

    private fun mapTypedef(type: Type.Delegated): String {
        val inner = type.type()
        if (inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER) {
            val pointee = inner.type()
            if (pointee is Type.Declared && pointee.tree().kind() == Declaration.Scoped.Kind.STRUCT) {
                val pointeeName = pointee.tree().name()
                val typedefName = type.name()
                return when {
                    pointeeName.isNotEmpty() && pointeeName.endsWith("Impl") && typedefName != null -> "$typedefName?"
                    pointeeName.isNotEmpty() && pointeeName.startsWith("WGPU") && pointeeName.endsWith("Impl") -> "${pointeeName.removeSuffix("Impl")}?"
                    pointeeName.isNotEmpty() && !pointeeName.contains("unnamed") -> "$pointeeName?"
                    else -> "NativeAddress?"
                }
            }
            return "NativeAddress?"
        }

        val innerMapped = mapType(inner)
        if (innerMapped != "NativeAddress" && innerMapped != "NativeAddress?" && !innerMapped.contains("unnamed")) {
            return innerMapped
        }
        val name = type.name()
        return if (name != null && !name.contains("unnamed")) name else "NativeAddress"
    }

    private fun mapFunctionPointer(pointee: Type): String = when {
        pointee is Type.Primitive && pointee.kind() == Type.Primitive.Kind.Char -> "CString?"
        pointee is Type.Function -> "NativeAddress?"
        pointee is Type.Delegated && pointee.kind() == Type.Delegated.Kind.TYPEDEF && pointee.type() is Type.Function -> "NativeAddress?"
        else -> mapPointer(pointee, charNullable = true)
    }

    private fun mapFunctionTypedef(type: Type.Delegated): String {
        val typedefName = type.name()
        val inner = type.type()
        return when {
            callbackFunction(type) != null && typedefName != null && typedefName.startsWith("WGPU") && typedefName.endsWith("Callback") -> "$typedefName?"
            inner is Type.Function -> "NativeAddress?"
            inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER && inner.type() is Type.Function -> "NativeAddress?"
            inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER ->
                if (typedefName != null && typedefName.startsWith("WGPU")) "$typedefName?" else "NativeAddress?"
            else -> {
                val innerMapped = mapType(inner)
                if (innerMapped != "NativeAddress" && !innerMapped.contains("unnamed")) innerMapped else typedefName ?: "NativeAddress"
            }
        }
    }

    private fun declaredName(type: Type.Declared): String {
        val name = type.tree().name()
        return if (name.isNotEmpty() && !name.contains("unnamed")) name else "NativeAddress"
    }

    private fun Type.callbackFunctionOrNull(): Type.Function? = when {
        this is Type.Delegated && kind() == Type.Delegated.Kind.TYPEDEF -> type().callbackFunctionOrNull()
        this is Type.Delegated && kind() == Type.Delegated.Kind.POINTER -> type().callbackFunctionOrNull()
        this is Type.Function -> this
        else -> null
    }

    private fun isReferenceTypedef(type: Type): Boolean = when (type) {
        is Type.Delegated -> when (type.kind()) {
            Type.Delegated.Kind.TYPEDEF -> isReferenceTypedef(type.type())
            Type.Delegated.Kind.POINTER -> true
            else -> isReferenceTypedef(type.type())
        }
        is Type.Declared -> type.tree().kind() in setOf(Declaration.Scoped.Kind.STRUCT, Declaration.Scoped.Kind.UNION)
        else -> false
    }

    private fun referenceTypeName(type: Type): String? = when (type) {
        is Type.Delegated -> (type.name() ?: referenceTypeName(type.type()))?.toPublicHandleName()
        is Type.Declared -> type.tree().name().takeIf { it.isNotEmpty() && !it.contains("unnamed") }?.toPublicHandleName()
        else -> null
    }

    private fun isGeneratedReferenceTypedef(type: Type): Boolean {
        val name = referenceTypeName(type)
        return name != null && name.startsWith("WGPU") && (isReferenceTypedef(type) || name in generatedStructNames)
    }

    private fun canonicalType(type: Type): Type = when {
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> canonicalType(type.type())
        else -> type
    }

    private fun String.toPublicHandleName(): String =
        if (startsWith("WGPU") && endsWith("Impl")) removeSuffix("Impl") else this
}
