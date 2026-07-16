package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type

internal object KotlinEnumSupport {
    fun resolveEnum(type: Type): Declaration.Scoped? = when {
        type is Type.Declared && type.tree().kind() == Declaration.Scoped.Kind.ENUM -> type.tree()
        type is Type.Delegated && type.kind() != Type.Delegated.Kind.POINTER -> resolveEnum(type.type())
        else -> null
    }

    fun isOptionsStyle(name: String): Boolean =
        name.endsWith("Options") || name.endsWith("Flags") || name.endsWith("Mask")
}
