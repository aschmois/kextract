package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type
import org.graphiks.kextract.callbacks.ValidatedCallbackInfoBinding
import org.graphiks.kextract.callbacks.ValidatedDirectFunctionBinding
import org.graphiks.kextract.kotlin.builders.SourceBuilder

class KotlinCallbackBindingEmitter(
    private val mapType: (Type) -> String,
) {
    fun emitCommon(
        builder: SourceBuilder,
        directBindings: List<ValidatedDirectFunctionBinding>,
        callbackInfoBindings: List<ValidatedCallbackInfoBinding>,
    ) {
        directBindings.forEach { emitDirectCommon(builder, it) }
        callbackInfoBindings.forEach { emitCallbackInfoFactory(builder, it) }
    }

    fun emitJvm(builder: SourceBuilder, bindings: List<ValidatedDirectFunctionBinding>) {
        bindings.forEach { binding ->
            val name = binding.function.name()
            builder.appendLine("@Suppress(\"UNUSED_VARIABLE\")")
            builder.appendLine("internal actual fun ${preflightName(binding)}() {")
            builder.indent()
            builder.appendLine("val address = ${name}_ADDR")
            builder.appendLine("val handle = ${name}_HANDLE")
            builder.unindent()
            builder.appendLine("}")
            builder.appendLine()
        }
    }

    fun emitNative(builder: SourceBuilder, bindings: List<ValidatedDirectFunctionBinding>) {
        bindings.forEach { binding ->
            builder.appendLine("internal actual fun ${preflightName(binding)}() = Unit")
            builder.appendLine()
        }
    }

    fun emitAndroid(builder: SourceBuilder, bindings: List<ValidatedDirectFunctionBinding>) {
        bindings.forEach { binding ->
            builder.appendLine("internal actual fun ${preflightName(binding)}() {")
            builder.indent()
            builder.appendLine("throw UnsupportedOperationException(")
            builder.indent()
            builder.appendLine("\"Android/JNA safe callback bindings are not supported; use raw bindings or an Android Native target\",")
            builder.unindent()
            builder.appendLine(")")
            builder.unindent()
            builder.appendLine("}")
            builder.appendLine()
        }
    }

    private fun emitDirectCommon(
        builder: SourceBuilder,
        binding: ValidatedDirectFunctionBinding,
    ) {
        builder.appendLine("internal expect fun ${preflightName(binding)}()")
        builder.appendLine()
        emitDirectRegistrationOverload(builder, binding)
        if (binding.routingUserdataParameter == null) {
            emitDirectRearmOverload(builder, binding)
        }
    }

    private fun emitDirectRegistrationOverload(
        builder: SourceBuilder,
        binding: ValidatedDirectFunctionBinding,
    ) {
        val parameters = applicationParameters(binding)
        val callbackType = binding.callback.typedef.name()
        builder.appendLine("@OptIn(CallbackRuntimeApi::class)")
        builder.appendLine("fun ${binding.function.name()}(")
        builder.indent()
        parameters.forEach { parameter ->
            builder.appendLine("${parameter.name}: ${mapType(parameter.variable.type())},")
        }
        emitRegistrationParameters(builder, callbackType)
        builder.unindent()
        builder.appendLine("): CallbackRegistration<$callbackType> {")
        builder.indent()
        emitValidatedLocals(builder, parameters)
        builder.appendLine("${preflightName(binding)}()")
        builder.appendLine("val prepared = $callbackType.prepare(")
        builder.indent()
        builder.appendLine("policy = policy,")
        builder.appendLine("onError = onError,")
        builder.appendLine("callback = callback,")
        builder.unindent()
        builder.appendLine(")")
        builder.appendLine("return CallbackRuntime.activateForNativeCall(prepared) { registration ->")
        builder.indent()
        builder.appendLine(rawCall(binding, parameters, "registration"))
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitDirectRearmOverload(
        builder: SourceBuilder,
        binding: ValidatedDirectFunctionBinding,
    ) {
        val parameters = applicationParameters(binding)
        val callbackType = binding.callback.typedef.name()
        builder.appendLine("@UnsafeCallbackRearmApi")
        builder.appendLine("fun rearmAfterNativeQuiescence(")
        builder.indent()
        parameters.forEach { parameter ->
            builder.appendLine("${parameter.name}: ${mapType(parameter.variable.type())},")
        }
        emitRegistrationParameters(builder, callbackType)
        builder.unindent()
        builder.appendLine("): CallbackRegistration<$callbackType> {")
        builder.indent()
        emitValidatedLocals(builder, parameters)
        builder.appendLine("${preflightName(binding)}()")
        builder.appendLine("val registration = $callbackType.rearmAfterNativeQuiescence(")
        builder.indent()
        builder.appendLine("policy = policy,")
        builder.appendLine("onError = onError,")
        builder.appendLine("callback = callback,")
        builder.unindent()
        builder.appendLine(")")
        builder.appendLine("try {")
        builder.indent()
        builder.appendLine(rawCall(binding, parameters, "registration"))
        builder.appendLine("return registration")
        builder.unindent()
        builder.appendLine("} catch (failure: Throwable) {")
        builder.indent()
        builder.appendLine("registration.close()")
        builder.appendLine("throw failure")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitCallbackInfoFactory(
        builder: SourceBuilder,
        binding: ValidatedCallbackInfoBinding,
    ) {
        val structType = binding.struct.name()
        val callbackType = binding.callback.typedef.name()
        builder.appendLine("/**")
        builder.appendLine(" * ${binding.owner.lifetime.name}: the owning native call copies the callback-info value or containing descriptor, so the allocator scope may close after the call while the registration remains live.")
        builder.appendLine(" *")
        builder.appendLine(" * This factory does not own [registration].")
        builder.appendLine(" */")
        builder.appendLine("fun $structType.Companion.allocate(")
        builder.indent()
        builder.appendLine("allocator: MemoryAllocator,")
        binding.mode?.let { mode ->
            builder.appendLine("mode: ${mode.type.name()},")
        }
        builder.appendLine("registration: CallbackRegistration<$callbackType>,")
        binding.applicationUserdataFields.forEach { field ->
            builder.appendLine("${field.name()}: NativeAddress? = null,")
        }
        builder.unindent()
        builder.appendLine("): $structType {")
        builder.indent()
        binding.mode?.let { mode -> emitModeValidation(builder, mode.allowedConstants.map(Declaration.Constant::name)) }
        builder.appendLine("val info = allocate(allocator)")
        binding.mode?.let { mode -> builder.appendLine("info.${mode.field.name()} = mode") }
        builder.appendLine("info.${binding.callbackField.name()} = registration.callback")
        builder.appendLine("info.${binding.routingUserdataField.name()} = registration.userdata")
        binding.applicationUserdataFields.forEach { field ->
            builder.appendLine("info.${field.name()} = ${field.name()}")
        }
        builder.appendLine("return info")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitModeValidation(builder: SourceBuilder, allowedConstants: List<String>) {
        builder.appendLine("require(")
        builder.indent()
        if (allowedConstants.isEmpty()) {
            builder.appendLine("false,")
        } else {
            allowedConstants.forEachIndexed { index, constant ->
                if (index > 0) builder.indent()
                val suffix = if (index == allowedConstants.lastIndex) "," else " ||"
                builder.appendLine("mode == $constant$suffix")
                if (index > 0) builder.unindent()
            }
        }
        builder.unindent()
        builder.appendLine(")")
    }

    private fun emitRegistrationParameters(builder: SourceBuilder, callbackType: String) {
        builder.appendLine("policy: CallbackPolicy,")
        builder.appendLine("onError: CallbackExceptionHandler = CallbackExceptionHandler.Default,")
        builder.appendLine("callback: $callbackType,")
    }

    private fun emitValidatedLocals(
        builder: SourceBuilder,
        parameters: List<RenderedParameter>,
    ) {
        parameters.forEach { parameter ->
            builder.appendLine("val ${parameter.validatedName} = ${parameter.name}")
        }
    }

    private fun rawCall(
        binding: ValidatedDirectFunctionBinding,
        applicationParameters: List<RenderedParameter>,
        registrationName: String,
    ): String {
        val arguments = binding.function.parameters().map { parameter ->
            when {
                parameter === binding.callbackParameter -> "$registrationName.callback"
                parameter === binding.routingUserdataParameter -> "$registrationName.userdata"
                else -> applicationParameters.single { it.variable === parameter }.validatedName
            }
        }
        return "${binding.function.name()}(${arguments.joinToString(", ")})"
    }

    private fun applicationParameters(binding: ValidatedDirectFunctionBinding): List<RenderedParameter> =
        binding.function.parameters().mapIndexedNotNull { index, parameter ->
            if (parameter === binding.callbackParameter || parameter === binding.routingUserdataParameter) {
                null
            } else {
                val name = parameter.name().takeIf(String::isNotEmpty) ?: "arg$index"
                RenderedParameter(
                    variable = parameter,
                    name = name,
                    validatedName = "validated${name.replaceFirstChar(Char::uppercaseChar)}",
                )
            }
        }

    private fun preflightName(binding: ValidatedDirectFunctionBinding): String =
        "${binding.function.name()}CallbackBindingPreflight"

    private data class RenderedParameter(
        val variable: Declaration.Variable,
        val name: String,
        val validatedName: String,
    )
}
