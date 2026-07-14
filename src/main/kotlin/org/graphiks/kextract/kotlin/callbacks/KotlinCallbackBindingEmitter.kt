package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type
import org.graphiks.kextract.callbacks.ValidatedCallbackInfoBinding
import org.graphiks.kextract.callbacks.ValidatedDirectFunctionBinding
import org.graphiks.kextract.kotlin.builders.SourceBuilder
import org.graphiks.kextract.kotlin.utils.KotlinIdentifierAllocator

class KotlinCallbackBindingEmitter(
    private val mapType: (Type) -> String,
) {
    fun emitCommon(
        builder: SourceBuilder,
        directBindings: List<KotlinDirectFunctionBindingModel>,
        callbackInfoBindings: List<ValidatedCallbackInfoBinding>,
        callbackModelsByCanonicalId: Map<String, KotlinCallbackModel>,
    ) {
        directBindings.forEach { emitDirectCommon(builder, it, callbackModelsByCanonicalId) }
        callbackInfoBindings.forEach { emitCallbackInfoFactory(builder, it, callbackModelsByCanonicalId) }
    }

    fun emitJvm(
        builder: SourceBuilder,
        bindings: List<KotlinDirectFunctionBindingModel>,
        toRawArgument: (String, Type) -> String,
    ) {
        bindings.forEach { model ->
            val binding = model.binding
            val name = binding.function.name()
            val parameters = applicationParameters(binding)
            builder.appendLine("@Suppress(\"UNUSED_VARIABLE\")")
            emitPreflightHeader(builder, binding, model.preflightName, parameters, actual = true)
            builder.indent()
            parameters.forEach { parameter ->
                builder.appendLine(
                    "val ${parameter.preparedName} = ${toRawArgument(parameter.name, parameter.variable.type())}",
                )
            }
            builder.appendLine("val address = ${name}_ADDR")
            builder.appendLine("val handle = ${name}_HANDLE")
            builder.appendLine("return { ${preparedCallLambdaParameters(binding)} ->")
            builder.indent()
            builder.appendLine("handle.invokeExact(")
            builder.indent()
            preparedPlatformArguments(binding, parameters, toRawArgument).forEach { argument ->
                builder.appendLine("$argument,")
            }
            builder.unindent()
            builder.appendLine(")")
            builder.unindent()
            builder.appendLine("}")
            builder.unindent()
            builder.appendLine("}")
            builder.appendLine()
        }
    }

    fun emitNative(
        builder: SourceBuilder,
        bindings: List<KotlinDirectFunctionBindingModel>,
        toNativeArgument: (String, Type) -> String,
    ) {
        bindings.forEach { model ->
            val binding = model.binding
            val parameters = applicationParameters(binding)
            emitPreflightHeader(builder, binding, model.preflightName, parameters, actual = true)
            builder.indent()
            parameters.forEach { parameter ->
                builder.appendLine(
                    "val ${parameter.preparedName} = ${toNativeArgument(parameter.name, parameter.variable.type())}",
                )
            }
            builder.appendLine("return { ${preparedCallLambdaParameters(binding)} ->")
            builder.indent()
            builder.appendLine("webgpu.native.${binding.function.name()}(")
            builder.indent()
            preparedPlatformArguments(binding, parameters, toNativeArgument).forEach { argument ->
                builder.appendLine("$argument,")
            }
            builder.unindent()
            builder.appendLine(")")
            builder.unindent()
            builder.appendLine("}")
            builder.unindent()
            builder.appendLine("}")
            builder.appendLine()
        }
    }

    fun emitAndroid(builder: SourceBuilder, bindings: List<KotlinDirectFunctionBindingModel>) {
        bindings.forEach { model ->
            val binding = model.binding
            val parameters = applicationParameters(binding)
            emitPreflightHeader(builder, binding, model.preflightName, parameters, actual = true)
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
        model: KotlinDirectFunctionBindingModel,
        callbackModelsByCanonicalId: Map<String, KotlinCallbackModel>,
    ) {
        val binding = model.binding
        emitPreflightHeader(
            builder,
            binding,
            model.preflightName,
            applicationParameters(binding),
            actual = false,
        )
        builder.appendLine()
        emitDirectRegistrationOverload(builder, binding, model.preflightName, callbackModelsByCanonicalId)
        if (binding.routingUserdataParameter == null) {
            emitDirectRearmOverload(builder, binding, model.preflightName, callbackModelsByCanonicalId)
        }
    }

    private fun emitDirectRegistrationOverload(
        builder: SourceBuilder,
        binding: ValidatedDirectFunctionBinding,
        preflightName: String,
        callbackModelsByCanonicalId: Map<String, KotlinCallbackModel>,
    ) {
        val parameters = applicationParameters(binding)
        val callbackType = callbackModelsByCanonicalId.getValue(binding.callback.id).typeName
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
        builder.appendLine("val preparedCall = ${preflightCall(preflightName, parameters)}")
        builder.appendLine("val prepared = $callbackType.prepare(")
        builder.indent()
        builder.appendLine("policy = policy,")
        builder.appendLine("onError = onError,")
        builder.appendLine("callback = callback,")
        builder.unindent()
        builder.appendLine(")")
        builder.appendLine("return CallbackRuntime.activateForNativeCall(prepared) { registration ->")
        builder.indent()
        builder.appendLine(preparedCallInvocation(binding, "registration"))
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitDirectRearmOverload(
        builder: SourceBuilder,
        binding: ValidatedDirectFunctionBinding,
        preflightName: String,
        callbackModelsByCanonicalId: Map<String, KotlinCallbackModel>,
    ) {
        val parameters = applicationParameters(binding)
        val callbackType = callbackModelsByCanonicalId.getValue(binding.callback.id).typeName
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
        builder.appendLine("val preparedCall = ${preflightCall(preflightName, parameters)}")
        builder.appendLine("val registration = $callbackType.rearmAfterNativeQuiescence(")
        builder.indent()
        builder.appendLine("policy = policy,")
        builder.appendLine("onError = onError,")
        builder.appendLine("callback = callback,")
        builder.unindent()
        builder.appendLine(")")
        builder.appendLine("try {")
        builder.indent()
        builder.appendLine(preparedCallInvocation(binding, "registration"))
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
        callbackModelsByCanonicalId: Map<String, KotlinCallbackModel>,
    ) {
        val structType = binding.struct.name()
        val callbackType = callbackModelsByCanonicalId.getValue(binding.callback.id).typeName
        val parameterNames = KotlinIdentifierAllocator(RESERVED_PARAMETER_NAMES)
        val applicationUserdataParameters = binding.applicationUserdataFields.mapIndexed { index, field ->
            RenderedCallbackInfoParameter(
                variable = field,
                name = parameterNames.allocate(field.name(), "arg$index"),
            )
        }
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
        applicationUserdataParameters.forEach { parameter ->
            builder.appendLine("${parameter.name}: NativeAddress? = null,")
        }
        builder.unindent()
        builder.appendLine("): $structType {")
        builder.indent()
        binding.mode?.let { mode -> emitModeValidation(builder, mode.allowedConstants.map(Declaration.Constant::name)) }
        builder.appendLine("val info = allocate(allocator)")
        binding.mode?.let { mode -> builder.appendLine("info.${mode.field.name()} = mode") }
        builder.appendLine("info.${binding.callbackField.name()} = registration.callback")
        builder.appendLine("info.${binding.routingUserdataField.name()} = registration.userdata")
        applicationUserdataParameters.forEach { parameter ->
            builder.appendLine("info.${parameter.variable.name()} = ${parameter.name}")
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

    private fun emitPreflightHeader(
        builder: SourceBuilder,
        binding: ValidatedDirectFunctionBinding,
        preflightName: String,
        parameters: List<RenderedParameter>,
        actual: Boolean,
        returnType: String = preparedCallType(binding),
    ) {
        val modifier = if (actual) "actual" else "expect"
        if (parameters.isEmpty()) {
            val suffix = if (actual) " {" else ""
            builder.appendLine("internal $modifier fun $preflightName(): $returnType$suffix")
            return
        }
        builder.appendLine("internal $modifier fun $preflightName(")
        builder.indent()
        parameters.forEach { parameter ->
            builder.appendLine("${parameter.name}: ${mapType(parameter.variable.type())},")
        }
        builder.unindent()
        val suffix = if (actual) " {" else ""
        builder.appendLine("): $returnType$suffix")
    }

    private fun preflightCall(
        preflightName: String,
        applicationParameters: List<RenderedParameter>,
    ): String = "$preflightName(${applicationParameters.joinToString(", ", transform = RenderedParameter::name)})"

    private fun preparedCallInvocation(
        binding: ValidatedDirectFunctionBinding,
        registrationName: String,
    ): String {
        val arguments = buildList {
            add("$registrationName.callback")
            if (binding.routingUserdataParameter != null) add("$registrationName.userdata")
        }
        return "preparedCall(${arguments.joinToString(", ")})"
    }

    private fun preparedPlatformArguments(
        binding: ValidatedDirectFunctionBinding,
        applicationParameters: List<RenderedParameter>,
        convertAddress: (String, Type) -> String,
    ): List<String> {
        val arguments = binding.function.parameters().map { parameter ->
            when {
                parameter === binding.callbackParameter -> convertAddress("callback", parameter.type())
                parameter === binding.routingUserdataParameter -> convertAddress("userdata", parameter.type())
                else -> applicationParameters.single { it.variable === parameter }.preparedName
            }
        }
        return arguments
    }

    private fun applicationParameters(binding: ValidatedDirectFunctionBinding): List<RenderedParameter> {
        val names = KotlinIdentifierAllocator(RESERVED_PARAMETER_NAMES)
        val parameters = binding.function.parameters().mapIndexedNotNull { index, parameter ->
            if (parameter === binding.callbackParameter || parameter === binding.routingUserdataParameter) {
                null
            } else {
                val name = names.allocate(parameter.name(), "arg$index")
                RenderedParameter(
                    variable = parameter,
                    name = name,
                    preparedName = "",
                )
            }
        }
        val preparedNames = KotlinIdentifierAllocator(
            RESERVED_PARAMETER_NAMES + parameters.map(RenderedParameter::name) + PLATFORM_LOCAL_NAMES,
        )
        return parameters.mapIndexed { index, parameter ->
            parameter.copy(
                preparedName = preparedNames.allocate(
                    "prepared${parameter.name.replaceFirstChar(Char::uppercaseChar)}",
                    "preparedArg$index",
                ),
            )
        }
    }

    private fun preparedCallLambdaParameters(binding: ValidatedDirectFunctionBinding): String =
        if (binding.routingUserdataParameter == null) "callback" else "callback, userdata"

    private fun preparedCallType(binding: ValidatedDirectFunctionBinding): String =
        if (binding.routingUserdataParameter == null) {
            "(NativeAddress?) -> Unit"
        } else {
            "(NativeAddress?, NativeAddress?) -> Unit"
        }

    private data class RenderedParameter(
        val variable: Declaration.Variable,
        val name: String,
        val preparedName: String,
    )

    private data class RenderedCallbackInfoParameter(
        val variable: Declaration.Variable,
        val name: String,
    )

    private companion object {
        val RESERVED_PARAMETER_NAMES = setOf(
            "callback",
            "failure",
            "policy",
            "onError",
            "registration",
            "prepared",
            "preparedCall",
            "allocator",
            "mode",
        )
        val PLATFORM_LOCAL_NAMES = setOf(
            "address",
            "handle",
            "callback",
            "userdata",
        )
    }
}
