// src/main/kotlin/org/openjdk/kextract/kotlin/utils/TypeMapper.kt
package org.graphiks.kextract.kotlin.utils

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type

/**
 * Maps C types to Kotlin types.
 * Handles primitives, pointers (nullable), structs, typedefs, and functions.
 */
object TypeMapper {
    /**
     * Maps a C type to its Kotlin equivalent.
     */
    fun map(type: Type): String = when {
        type is Type.Primitive -> mapPrimitive(type.kind())
        type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> {
            // All pointers map to non-nullable MemorySegment
            // Null pointers are represented as MemorySegment.NULL in Panama
            "MemorySegment"
        }

        type is Type.Delegated && type.kind() == Type.Delegated.Kind.TYPEDEF -> {
            // If the typedef wraps a pointer (e.g. instancetype, id, NSObject*),
            // return MemorySegment directly rather than the typedef name (which is not generated).
            val inner = type.type()
            if (inner is Type.Delegated && inner.kind() == Type.Delegated.Kind.POINTER) {
                "MemorySegment"
            } else {
                val name = type.name() ?: "Any"
                // ObjC `Class` is a typedef (struct objc_class *); if the pointer check
                // above somehow missed it, map to MemorySegment so that generated code
                // compiles (raw `Class` is invalid Kotlin — requires `Class<*>`),
                // and semantically a Class pointer IS a MemorySegment at the FFM level.
                if (name == "Class") "MemorySegment" else sanitizeName(name)
            }
        }

        type is Type.Declared -> {
            val tree = type.tree()
            when (tree.kind()) {
                Declaration.Scoped.Kind.STRUCT,
                Declaration.Scoped.Kind.UNION -> "MemorySegment"
                Declaration.Scoped.Kind.ENUM ->
                    if (tree.name().isNotEmpty()) tree.name() else "Long"
                else -> "Long"
            }
        }
        type is Type.Function -> mapFunctionType(type)
        type is Type.Array -> "MemorySegment"
        else -> "Any"
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
        else -> "Any"
    }

    private fun mapFunctionType(type: Type.Function): String {
        // For function return types, we just map the return type itself
        // The function signature is handled separately in KotlinHeaderBuilder
        return map(type.returnType())
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_]"), "_")
            .replace(Regex("^\\d+"), "_")
}
