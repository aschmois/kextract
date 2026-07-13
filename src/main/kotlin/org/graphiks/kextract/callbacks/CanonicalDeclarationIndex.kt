package org.graphiks.kextract.callbacks

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type

/** Canonical declaration lookup built from filtered declarations with their original C names. */
class CanonicalDeclarationIndex(root: Declaration.Scoped) {
    private val declarations = linkedMapOf<String, MutableList<Declaration>>()

    init {
        root.members().forEach(::index)
    }

    fun requireTypedef(id: String): Declaration.Typedef = require(id, "typedef")

    fun requireStruct(id: String): Declaration.Scoped {
        val declaration = require<Declaration.Scoped>(id, "struct")
        if (declaration.kind() != Declaration.Scoped.Kind.STRUCT) {
            throw CallbackBindingsException("$id: canonical declaration is not a struct")
        }
        return declaration
    }

    fun requireFunction(id: String): Declaration.Function = require(id, "function")

    fun requireConstant(id: String): Declaration.Constant = require(id, "constant")

    fun typedefIds(): List<String> = declarations.keys.filter { it.startsWith("typedef:") }

    private inline fun <reified T : Declaration> require(id: String, kind: String): T {
        val matches = declarations[id].orEmpty()
        if (matches.isEmpty()) {
            throw CallbackBindingsException("$id: canonical declaration does not exist")
        }
        if (matches.size != 1) {
            throw CallbackBindingsException(
                "$id: canonical declaration resolves ${matches.size} times; expected exactly once",
            )
        }
        return matches.single() as? T
            ?: throw CallbackBindingsException("$id: canonical declaration is not a $kind")
    }

    private fun index(declaration: Declaration) {
        if (Skip.isPresent(declaration)) return
        when (declaration) {
            is Declaration.Typedef -> add("typedef:${declaration.name()}", declaration)
            is Declaration.Function -> add("function:${declaration.name()}", declaration)
            is Declaration.Constant -> add("constant:${declaration.name()}", declaration)
            is Declaration.Scoped -> {
                if (declaration.kind() == Declaration.Scoped.Kind.STRUCT) {
                    add("struct:${declaration.name()}", declaration)
                }
                if (declaration.kind() == Declaration.Scoped.Kind.ENUM ||
                    declaration.kind() == Declaration.Scoped.Kind.TOPLEVEL
                ) {
                    declaration.members().forEach(::index)
                }
            }
        }
    }

    private fun add(id: String, declaration: Declaration) {
        declarations.getOrPut(id) { mutableListOf() }.add(declaration)
    }
}

internal object CallbackTypeResolver {
    fun exactFunctionPointer(type: Type): Type.Function? {
        val pointer = unwrapAliasesAndQualifiers(type) as? Type.Delegated ?: return null
        if (pointer.kind() != Type.Delegated.Kind.POINTER) return null
        return unwrapAliasesAndQualifiers(pointer.type()) as? Type.Function
    }

    fun isCanonicalVoid(type: Type): Boolean {
        val canonical = unwrapAliasesAndQualifiers(type)
        return canonical is Type.Primitive && canonical.kind() == Type.Primitive.Kind.Void
    }

    fun isOpaquePointer(type: Type): Boolean {
        val pointer = when (type) {
            is Type.Delegated -> when (type.kind()) {
                Type.Delegated.Kind.POINTER -> type
                Type.Delegated.Kind.TYPEDEF,
                Type.Delegated.Kind.ATOMIC,
                Type.Delegated.Kind.VOLATILE -> return isOpaquePointer(type.type())
                else -> return false
            }
            else -> return false
        }
        val pointee = unwrapAliases(pointer.type())
        return (pointee is Type.Primitive && pointee.kind() == Type.Primitive.Kind.Void) ||
            (pointee is Type.Declared && pointee.tree().members().isEmpty())
    }

    fun canonicalTypedefId(type: Type): String? = when (type) {
        is Type.Delegated -> when (type.kind()) {
            Type.Delegated.Kind.TYPEDEF -> type.name()?.let {
                "typedef:${normalizeTypedefName(it)}"
            }
            Type.Delegated.Kind.ATOMIC,
            Type.Delegated.Kind.VOLATILE,
            Type.Delegated.Kind.SIGNED,
            Type.Delegated.Kind.UNSIGNED -> canonicalTypedefId(type.type())
            else -> null
        }
        else -> null
    }

    fun belongsToTypedef(type: Type, typedef: Declaration.Typedef): Boolean {
        val configuredTokens = nominalTypeTokens(typedef.type()) + "typedef:${typedef.name()}"
        return nominalTypeTokens(type).any { it in configuredTokens }
    }

    fun structFromType(type: Type): Declaration.Scoped? = when (type) {
        is Type.Declared -> type.tree().takeIf { it.kind() == Declaration.Scoped.Kind.STRUCT }
        is Type.Delegated -> structFromType(type.type())
        else -> null
    }

    fun isStructType(type: Type, target: Declaration.Scoped): Boolean {
        val actual = structFromType(type) ?: return false
        return actual === target ||
            (actual.kind() == target.kind() && actual.name() == target.name())
    }

    fun sameNormalizedType(left: Type, right: Type): Boolean =
        normalizedType(left) == normalizedType(right)

    fun describeType(type: Type): String = canonicalTypedefId(type) ?: when (type) {
        is Type.Primitive -> type.kind().typeName()
        is Type.Delegated -> when (type.kind()) {
            Type.Delegated.Kind.POINTER -> "${describeType(type.type())} *"
            Type.Delegated.Kind.SIGNED -> "signed ${describeType(type.type())}"
            Type.Delegated.Kind.UNSIGNED -> "unsigned ${describeType(type.type())}"
            else -> describeType(type.type())
        }
        else -> type.toString()
    }

    private fun unwrapAliasesAndQualifiers(type: Type): Type = when (type) {
        is Type.Delegated -> when (type.kind()) {
            Type.Delegated.Kind.TYPEDEF,
            Type.Delegated.Kind.ATOMIC,
            Type.Delegated.Kind.VOLATILE -> unwrapAliasesAndQualifiers(type.type())
            else -> type
        }
        else -> type
    }

    private fun unwrapAliases(type: Type): Type = when (type) {
        is Type.Delegated -> when (type.kind()) {
            Type.Delegated.Kind.TYPEDEF,
            Type.Delegated.Kind.ATOMIC,
            Type.Delegated.Kind.VOLATILE,
            Type.Delegated.Kind.SIGNED,
            Type.Delegated.Kind.UNSIGNED -> unwrapAliases(type.type())
            else -> type
        }
        else -> type
    }

    private fun nominalTypeTokens(type: Type): Set<String> = when (type) {
        is Type.Declared -> setOf("${type.tree().kind().name.lowercase()}:${type.tree().name()}")
        is Type.Delegated -> when (type.kind()) {
            Type.Delegated.Kind.TYPEDEF ->
                nominalTypeTokens(type.type()) + "typedef:${normalizeTypedefName(type.name().orEmpty())}"
            Type.Delegated.Kind.ATOMIC,
            Type.Delegated.Kind.VOLATILE -> nominalTypeTokens(type.type())
            else -> emptySet()
        }
        else -> emptySet()
    }

    private fun normalizedType(type: Type): String = when (type) {
        is Type.Primitive -> type.kind().typeName()
        is Type.Declared -> "${type.tree().kind().name.lowercase()}:${type.tree().name()}"
        is Type.Function -> "(${type.argumentTypes().joinToString { normalizedType(it) }})" +
            "->${normalizedType(type.returnType())}"
        is Type.Array -> "${type.kind().name.lowercase()}[${type.elementCount() ?: ""}]" +
            normalizedType(type.elementType())
        is Type.Delegated -> when (type.kind()) {
            Type.Delegated.Kind.TYPEDEF,
            Type.Delegated.Kind.ATOMIC,
            Type.Delegated.Kind.VOLATILE -> normalizedType(type.type())
            else -> "${type.kind().name.lowercase()}:${normalizedType(type.type())}"
        }
        else -> type.toString()
    }

    private fun normalizeTypedefName(name: String): String = name
        .replace(Regex("\\b(const|volatile|restrict)\\b"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
