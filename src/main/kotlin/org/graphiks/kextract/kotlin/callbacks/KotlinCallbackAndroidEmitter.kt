package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.kotlin.KotlinKmpNamePlan
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_EXCEPTION_HANDLER
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_POLICY
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_REGISTRATION
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.CALLBACK_RUNTIME_API
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.OPT_IN
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.PREPARED_CALLBACK_REGISTRATION
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.UNSAFE_CALLBACK_REARM_API
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.UNSUPPORTED_OPERATION_EXCEPTION
import org.graphiks.kextract.kotlin.builders.SourceBuilder

internal class KotlinCallbackAndroidEmitter(
    private val namePlan: KotlinKmpNamePlan,
) {
    fun emit(builder: SourceBuilder, callbacks: List<KotlinCallbackModel>) {
        callbacks.forEach { callback ->
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
    }

    private fun emitRegistrationOperation(
        builder: SourceBuilder,
        callback: KotlinCallbackModel,
        operation: String,
        internal: Boolean,
    ) {
        builder.appendLine("@${namePlan.runtime(OPT_IN)}(${namePlan.runtime(CALLBACK_RUNTIME_API)}::class)")
        val visibility = if (internal) "internal " else ""
        builder.appendLine("${visibility}actual fun ${callback.typeName}.Companion.$operation(")
        builder.indent()
        builder.appendLine("policy: ${namePlan.runtime(CALLBACK_POLICY)},")
        builder.appendLine("onError: ${namePlan.runtime(CALLBACK_EXCEPTION_HANDLER)},")
        builder.appendLine("callback: ${callback.typeName},")
        builder.unindent()
        val registrationType = if (internal) namePlan.runtime(PREPARED_CALLBACK_REGISTRATION) else namePlan.runtime(CALLBACK_REGISTRATION)
        builder.appendLine("): $registrationType<${callback.typeName}> {")
        builder.indent()
        builder.appendLine("throw ${namePlan.runtime(UNSUPPORTED_OPERATION_EXCEPTION)}(")
        builder.indent()
        builder.appendLine("\"Android/JNA callback registration is not supported; use raw bindings or an Android Native target\",")
        builder.unindent()
        builder.appendLine(")")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }
}
