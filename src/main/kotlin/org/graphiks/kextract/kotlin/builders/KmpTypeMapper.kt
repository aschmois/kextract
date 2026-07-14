package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.KotlinKmpNamePlan
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.ARRAY_HOLDER
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.C_STRING
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.NATIVE_ADDRESS
import org.graphiks.kextract.pipeline.isEnum

internal class KmpTypeMapper(
    private val opaqueHandleAliases: Map<String, String>,
    private val generatedStructNames: Set<String>,
    private val namePlan: KotlinKmpNamePlan,
    private val arraysAsHolders: Boolean = true,
) {
    private val nativeAddress = namePlan.runtime(NATIVE_ADDRESS)
    private val cString = namePlan.runtime(C_STRING)
    private val arrayHolder = namePlan.runtime(ARRAY_HOLDER)

    fun mapType(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED -> mapUnsigned(type.type())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> mapPointer(type.type(), charNullable = false)
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> mapTypedef(type)
        type is Type.Declared -> declaredName(type)
        else -> nativeAddress
    }

    fun mapFunctionType(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED -> mapType(type)
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> mapFunctionPointer(type.type())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> mapFunctionTypedef(type)
        type is Type.Function -> "$nativeAddress?"
        type is Type.Declared -> declaredName(type)
        type is Type.Array && arraysAsHolders -> "$arrayHolder<${mapFunctionType(type.elementType()).removeSuffix("?")}>?"
        else -> nativeAddress
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
        else -> nativeAddress
    }

    fun callbackFunction(type: Type): Type.Function? = type.callbackFunctionOrNull()

    fun pointerDepth(type: Type): Int = when {
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER ->
            1 + pointerDepth(type.type())
        type is Type.Delegated -> pointerDepth(type.type())
        else -> 0
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
            fieldType != nativeAddress &&
            fieldType != cString &&
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
        pointerDepth(pointee) > 0 -> "$nativeAddress?"
        pointee is Type.Primitive && pointee.kind() == Type.Primitive.Kind.Char -> if (charNullable) "$cString?" else cString
        pointee is Type.Delegated && isGeneratedReferenceTypedef(pointee) -> "${referenceTypeName(pointee)}?"
        pointee is Type.Declared && pointee.tree().kind() in setOf(Declaration.Scoped.Kind.STRUCT, Declaration.Scoped.Kind.UNION) -> {
            val name = pointee.tree().name()
            opaqueHandleAliases[name]?.let { "$it?" }
                ?: name.takeIf { it.endsWith("Impl") }?.removeSuffix("Impl")?.let { "$it?" }
                ?: if (name.isNotEmpty() && !name.contains("unnamed")) "${namePlan.declaration(pointee.tree())}?" else "$nativeAddress?"
        }
        else -> "$nativeAddress?"
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
                    pointeeName.isNotEmpty() && pointeeName.endsWith("Impl") -> "${pointeeName.removeSuffix("Impl")}?"
                    pointeeName.isNotEmpty() && !pointeeName.contains("unnamed") -> "$pointeeName?"
                    else -> "$nativeAddress?"
                }
            }
            return "$nativeAddress?"
        }

        val innerMapped = mapType(inner)
        if (innerMapped != nativeAddress && innerMapped != "$nativeAddress?" && !innerMapped.contains("unnamed")) {
            return innerMapped
        }
        val name = type.name()
        return if (name != null && !name.contains("unnamed")) name else nativeAddress
    }

    private fun mapFunctionPointer(pointee: Type): String = when {
        pointee is Type.Primitive && pointee.kind() == Type.Primitive.Kind.Char -> "$cString?"
        pointee is Type.Function -> "$nativeAddress?"
        pointee is Type.Delegated && pointee.kind() == Type.Delegated.Kind.TYPEDEF && pointee.type() is Type.Function -> "$nativeAddress?"
        else -> mapPointer(pointee, charNullable = true)
    }

    private fun mapFunctionTypedef(type: Type.Delegated): String {
        val typedefName = type.name()
        val inner = type.type()
        return when {
            callbackFunction(type) != null -> "$nativeAddress?"
            inner is Type.Function -> "$nativeAddress?"
            inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER && inner.type() is Type.Function -> "$nativeAddress?"
            inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER ->
                if (typedefName != null && isGeneratedReferenceTypedef(type)) "$typedefName?" else "$nativeAddress?"
            else -> {
                val innerMapped = mapType(inner)
                if (innerMapped != nativeAddress && !innerMapped.contains("unnamed")) innerMapped else typedefName ?: nativeAddress
            }
        }
    }

    private fun declaredName(type: Type.Declared): String {
        val name = type.tree().name()
        return if (name.isNotEmpty() && !name.contains("unnamed")) namePlan.declaration(type.tree()) else nativeAddress
    }

    private fun Type.callbackFunctionOrNull(): Type.Function? = when {
        this is Type.Delegated && kind() == Type.Delegated.Kind.TYPEDEF -> type().callbackFunctionOrNull()
        this is Type.Delegated && kind() == Type.Delegated.Kind.POINTER -> type().callbackFunctionOrNull()
        this is Type.Function -> this
        else -> null
    }

    private fun referenceTypeName(type: Type): String? = when (type) {
        is Type.Delegated -> (type.name() ?: referenceTypeName(type.type()))?.toPublicHandleName()
        is Type.Declared -> type.tree().name().takeIf { it.isNotEmpty() && !it.contains("unnamed") }?.toPublicHandleName()
        else -> null
    }

    private fun isGeneratedReferenceTypedef(type: Type): Boolean {
        val name = referenceTypeName(type)
        return name != null && (name in opaqueHandleAliases.values || name in generatedStructNames)
    }

    private fun canonicalType(type: Type): Type = when {
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> canonicalType(type.type())
        else -> type
    }

    private fun String.toPublicHandleName(): String =
        if (endsWith("Impl")) removeSuffix("Impl") else this
}
