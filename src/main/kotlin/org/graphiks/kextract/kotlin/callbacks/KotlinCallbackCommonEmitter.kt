package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.builders.SourceBuilder

class KotlinCallbackCommonEmitter(
    private val mapType: (Type) -> String,
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
            builder.appendLine("@UnsafeCallbackRearmApi")
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
        builder.appendLine("fun interface ${callback.typeName} : Callback {")
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
        builder.appendLine("@CallbackRuntimeApi")
        builder.appendLine("internal val ${callback.typeName}Type: CallbackType<${callback.typeName}> = CallbackType(")
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
        if (internal) builder.appendLine("@CallbackRuntimeApi")
        val visibility = if (internal) "internal " else ""
        builder.appendLine("${visibility}expect fun ${callback.typeName}.Companion.$operation(")
        builder.indent()
        builder.appendLine("policy: CallbackPolicy,")
        builder.appendLine("onError: CallbackExceptionHandler = CallbackExceptionHandler.Default,")
        builder.appendLine("callback: ${callback.typeName},")
        builder.unindent()
        val registrationType = if (internal) "PreparedCallbackRegistration" else "CallbackRegistration"
        builder.appendLine("): $registrationType<${callback.typeName}>")
        builder.appendLine()
    }
}
