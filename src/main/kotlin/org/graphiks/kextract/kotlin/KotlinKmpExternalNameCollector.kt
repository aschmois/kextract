@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package org.graphiks.kextract.kotlin

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import org.graphiks.kextract.callbacks.ValidatedCallbackBindings

internal object KotlinKmpExternalNameCollector {
    fun collect(
        scoped: Declaration.Scoped,
        callbackBindings: ValidatedCallbackBindings,
    ): Set<String> {
        val collector = Collector(callbackBindings)
        scoped.accept(collector)
        return collector.names
    }

    private class Collector(
        callbackBindings: ValidatedCallbackBindings,
    ) : Declaration.Visitor<Unit> {
        val names = linkedSetOf<String>()
        private val callbackTypedefs = callbackBindings.callbacks.map { it.typedef }

        override fun visitScoped(decl: Declaration.Scoped) {
            if (Skip.isPresent(decl)) return
            when (decl.kind()) {
                Declaration.Scoped.Kind.TOPLEVEL -> {
                    collectFlagTypedefNames(decl)
                    decl.members().forEach { it.accept(this) }
                }

                Declaration.Scoped.Kind.STRUCT,
                Declaration.Scoped.Kind.UNION,
                -> {
                    val name = decl.name()
                    if (name.isEmpty() || name.contains("unnamed")) return
                    if (name.endsWith("Impl") && decl.members().isEmpty()) return
                    names += name
                }

                Declaration.Scoped.Kind.ENUM -> {
                    val name = decl.name()
                    if (name.isEmpty() || name.contains("unnamed")) return
                    names += name
                    decl.members()
                        .filterIsInstance<Declaration.Constant>()
                        .filterNot(Skip::isPresent)
                        .forEach { names += it.name() }
                }

                else -> Unit
            }
        }

        override fun visitFunction(decl: Declaration.Function) {
            if (!Skip.isPresent(decl)) names += decl.name()
        }

        override fun visitTypedef(decl: Declaration.Typedef) {
            if (Skip.isPresent(decl) || callbackTypedefs.any { it === decl }) return
            val name = decl.name()
            if (name.isEmpty()) return
            val inner = decl.type()
            if (inner !is Type.Delegated || inner.kind() != Type.Delegated.Kind.POINTER) return
            val pointee = inner.type()
            if (pointee !is Type.Declared || pointee.tree().kind() != Declaration.Scoped.Kind.STRUCT) return
            val pointeeName = pointee.tree().name()
            if (pointeeName.isNotEmpty() && pointeeName.endsWith("Impl")) names += name
        }

        override fun visitVariable(decl: Declaration.Variable) = Unit
        override fun visitConstant(decl: Declaration.Constant) = Unit
        override fun visitObjCClass(decl: Declaration.ObjCClass) = Unit
        override fun visitObjCProtocol(decl: Declaration.ObjCProtocol) = Unit
        override fun visitObjCCategory(decl: Declaration.ObjCCategory) = Unit

        private fun collectFlagTypedefNames(decl: Declaration.Scoped) {
            val constants = decl.members()
                .filterIsInstance<Declaration.Constant>()
                .filterNot(Skip::isPresent)
            decl.members()
                .filterIsInstance<Declaration.Typedef>()
                .filterNot(Skip::isPresent)
                .filter { typedef ->
                    typedef.name() != "WGPUFlags" &&
                        constants.any { it.name().startsWith("${typedef.name()}_") }
                }
                .forEach { typedef ->
                    names += typedef.name()
                    constants
                        .filter { it.name().startsWith("${typedef.name()}_") }
                        .forEach { names += it.name() }
                }
        }
    }
}
