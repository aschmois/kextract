package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.KotlinKmpNamePlan
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_EXCEPTION_HANDLER
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_POLICY
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_REGISTRATION
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_RUNTIME_API
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_TYPE
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.PREPARED_CALLBACK_REGISTRATION
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.UNSAFE_CALLBACK_REARM_API
import org.graphiks.kextract.kotlin.builders.SourceBuilder

internal class KotlinCallbackCommonEmitter(
    private val mapType: (Type) -> String,
    private val namePlan: KotlinKmpNamePlan,
) {
    fun emit(builder: SourceBuilder, callbacks: List<KotlinCallbackModel>) {
        callbacks.forEach { callback -> emit(builder, callback) }
    }

    private fun emit(builder: SourceBuilder, callback: KotlinCallbackModel) {
        emitSam(builder, callback)
        emitCallbackType(builder, callback)
        emitRegistrationOperation(builder, callback, "register", internal = false)
        emitRegistrationOperation(builder, callback, "prepare", internal = true)
        if (!callback.hasRoutingUserdata) {
            builder.appendLine("@${namePlan.runtime(UNSAFE_CALLBACK_REARM_API)}")
            emitRegistrationOperation(
                builder,
                callback,
                "rearmAfterNativeQuiescence",
                internal = false,
            )
        }
    }

    private fun emitSam(builder: SourceBuilder, callback: KotlinCallbackModel) {
        callback.documentation?.let { documentation ->
            builder.appendLine("/**")
            documentation.lines().forEach { line ->
                if (line.isBlank()) {
                    builder.appendLine(" *")
                } else {
                    builder.appendLine(" * ${line.replace("*/", "* /").replace("/*", "/ *")}")
                }
            }
            builder.appendLine(" */")
        }
        builder.appendLine("fun interface ${callback.typeName} : ${namePlan.runtime(CALLBACK)} {")
        builder.indent()
        if (callback.parameters.size <= 2) {
            val parameters = callback.parameters.joinToString(", ") { "${it.name}: ${mapType(it.type)}" }
            builder.appendLine("fun invoke($parameters)")
        } else {
            builder.appendLine("fun invoke(")
            builder.indent()
            callback.parameters.forEach { parameter ->
                builder.appendLine("${parameter.name}: ${mapType(parameter.type)},")
            }
            builder.unindent()
            builder.appendLine(")")
            builder.unindent()
            builder.appendLine()
            builder.indent()
        }
        builder.appendLine("companion object")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitCallbackType(builder: SourceBuilder, callback: KotlinCallbackModel) {
        val callbackType = namePlan.runtime(CALLBACK_TYPE)
        builder.appendLine("@${namePlan.runtime(CALLBACK_RUNTIME_API)}")
        builder.appendLine("internal val ${callback.runtimeTypeName}: $callbackType<${callback.typeName}> = $callbackType(")
        builder.indent()
        builder.appendLine("canonicalId = \"${callback.canonicalId}\",")
        builder.appendLine("hasRoutingUserdata = ${callback.hasRoutingUserdata},")
        builder.unindent()
        builder.appendLine(")")
        builder.appendLine()
    }

    private fun emitRegistrationOperation(
        builder: SourceBuilder,
        callback: KotlinCallbackModel,
        operation: String,
        internal: Boolean,
    ) {
        if (internal) builder.appendLine("@${namePlan.runtime(CALLBACK_RUNTIME_API)}")
        val visibility = if (internal) "internal " else ""
        builder.appendLine("${visibility}expect fun ${callback.typeName}.Companion.$operation(")
        builder.indent()
        val callbackPolicy = namePlan.runtime(CALLBACK_POLICY)
        val callbackExceptionHandler = namePlan.runtime(CALLBACK_EXCEPTION_HANDLER)
        builder.appendLine("policy: $callbackPolicy,")
        builder.appendLine("onError: $callbackExceptionHandler = $callbackExceptionHandler.Default,")
        builder.appendLine("callback: ${callback.typeName},")
        builder.unindent()
        val registrationType = if (internal) {
            namePlan.runtime(PREPARED_CALLBACK_REGISTRATION)
        } else {
            namePlan.runtime(CALLBACK_REGISTRATION)
        }
        builder.appendLine("): $registrationType<${callback.typeName}>")
        builder.appendLine()
    }
}
