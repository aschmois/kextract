package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type

internal object KotlinEnumSupport {
    fun resolveEnum(type: Type): Declaration.Scoped? = when {
        type is Type.Declared &&
            type.tree().kind() == Declaration.Scoped.Kind.ENUM &&
            isNamedClangEnum(type.tree().name()) -> type.tree()
        type is Type.Delegated && type.kind() != Type.Delegated.Kind.POINTER -> resolveEnum(type.type())
        else -> null
    }

    fun isOptionsStyle(name: String): Boolean =
        name.endsWith("Options") || name.endsWith("Flags") || name.endsWith("Mask")

    /**
     * Clang preserves extension and Unicode identifiers (for example names containing `$`),
     * but synthesizes descriptions such as `enum (unnamed at ...)` for anonymous declarations.
     * Parentheses cannot occur in a C identifier, so rejecting only these pseudo-name markers
     * keeps real non-empty Clang names without imposing an ASCII-only identifier grammar.
     */
    private fun isNamedClangEnum(name: String): Boolean =
        name.isNotEmpty() &&
            !name.contains("(unnamed", ignoreCase = true) &&
            !name.contains("(anonymous", ignoreCase = true)
}
