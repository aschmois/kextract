package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.kotlin.builders.SourceBuilder

class KotlinCallbackAndroidEmitter {
    fun emit(builder: SourceBuilder, callbacks: List<KotlinCallbackModel>) {
        callbacks.forEach { callback ->
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
    }

    private fun emitRegistrationOperation(
        builder: SourceBuilder,
        callback: KotlinCallbackModel,
        operation: String,
        internal: Boolean,
    ) {
        builder.appendLine("@OptIn(CallbackRuntimeApi::class)")
        val visibility = if (internal) "internal " else ""
        builder.appendLine("${visibility}actual fun ${callback.typeName}.Companion.$operation(")
        builder.indent()
        builder.appendLine("policy: CallbackPolicy,")
        builder.appendLine("onError: CallbackExceptionHandler,")
        builder.appendLine("callback: ${callback.typeName},")
        builder.unindent()
        val registrationType = if (internal) "PreparedCallbackRegistration" else "CallbackRegistration"
        builder.appendLine("): $registrationType<${callback.typeName}> {")
        builder.indent()
        builder.appendLine("throw UnsupportedOperationException(")
        builder.indent()
        builder.appendLine("\"Android/JNA callback registration is not supported; use raw bindings or an Android Native target\",")
        builder.unindent()
        builder.appendLine(")")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }
}
