package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.builders.SourceBuilder
import org.graphiks.kextract.pipeline.isEnum

class KotlinCallbackNativeEmitter(
    private val mapType: (Type) -> String,
) {
    fun emit(builder: SourceBuilder, callbacks: List<KotlinCallbackModel>) {
        callbacks.forEach { callback ->
            emitTrampoline(builder, callback)
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

    private fun emitTrampoline(builder: SourceBuilder, callback: KotlinCallbackModel) {
        val rawParameters = callback.rawParameters()
        val functionTypes = rawParameters.map { it.cAbiType.nativeCarrier } + "Unit"
        val parameterNames = rawParameters.joinToString(", ") { it.name }
        val lambdaStart = if (parameterNames.isEmpty()) "{" else "{ $parameterNames ->"

        builder.appendLine("@OptIn(CallbackRuntimeApi::class)")
        builder.appendLine(
            "private val ${callback.trampolineName} = " +
                "staticCFunction<${functionTypes.joinToString(", ")}> $lambdaStart",
        )
        builder.indent()
        builder.appendLine("try {")
        builder.indent()
        builder.appendLine("CallbackRuntime.dispatchSafely(")
        builder.indent()
        builder.appendLine("type = ${callback.runtimeTypeName},")
        val routingUserdata = callback.routingUserdataParameter?.name?.let { "$it?.let(::NativeAddress)" } ?: "null"
        builder.appendLine("userdata = $routingUserdata,")
        builder.unindent()
        builder.appendLine(") { callback ->")
        builder.indent()
        emitInvocation(builder, callback)
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("} catch (failure: Throwable) {")
        builder.indent()
        builder.appendLine("CallbackRuntime.reportUnroutedFailure(failure)")
        builder.unindent()
        builder.appendLine("}")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitInvocation(builder: SourceBuilder, callback: KotlinCallbackModel) {
        if (callback.parameters.size <= 1) {
            val arguments = callback.parameters.joinToString(", ") { adaptNativeArgument(it) }
            builder.appendLine("callback.invoke($arguments)")
            return
        }

        builder.appendLine("callback.invoke(")
        builder.indent()
        callback.parameters.forEach { parameter ->
            builder.appendLine("${adaptNativeArgument(parameter)},")
        }
        builder.unindent()
        builder.appendLine(")")
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
        builder.appendLine("): $registrationType<${callback.typeName}> = CallbackRuntime.$operation(")
        builder.indent()
        builder.appendLine("type = ${callback.runtimeTypeName},")
        builder.appendLine("trampoline = NativeAddress(${callback.trampolineName}),")
        builder.appendLine("policy = policy,")
        builder.appendLine("onError = onError,")
        builder.appendLine("callback = callback,")
        builder.unindent()
        builder.appendLine(")")
        builder.appendLine()
    }

    private fun adaptNativeArgument(parameter: KotlinCallbackParameter): String {
        val name = parameter.name
        val type = parameter.type
        val cAbiType = parameter.cAbiType
        val mapped = mapType(type)
        return when {
            isEnum(type) && isOptionsStyle(mapped) -> "$mapped($name.toLong())"
            isEnum(type) -> enumApplicationValue(name, mapped, cAbiType)
            cAbiType is KotlinCallbackCAbiType.StructValue -> "$mapped.ByValue($name)"
            cAbiType is KotlinCallbackCAbiType.Address && mapped == "NativeAddress?" ->
                "$name?.let(::NativeAddress)"
            cAbiType is KotlinCallbackCAbiType.Address && mapped == "CString?" ->
                "$name?.let(::NativeAddress)?.let(::CString)"
            cAbiType is KotlinCallbackCAbiType.Address && mapped.endsWith("?") -> {
                val nonNullable = mapped.removeSuffix("?")
                if (cAbiType.pointerDepth > 1) {
                    "$name?.reinterpret<COpaquePointerVar>()?.pointed?.value" +
                        "?.let(::NativeAddress)?.let { $nonNullable(it) }"
                } else {
                    "$name?.let(::NativeAddress)?.let { $nonNullable(it) }"
                }
            }
            else -> name
        }
    }

    private fun enumApplicationValue(
        name: String,
        mapped: String,
        cAbiType: KotlinCallbackCAbiType,
    ): String {
        val scalar = cAbiType as? KotlinCallbackCAbiType.Scalar
            ?: error("Enum callback parameter must have a scalar C ABI type")
        return when (scalar.nativeCarrier) {
            "ULong" -> "$name.toULong() as $mapped"
            "UInt" -> "$name.toUInt() as $mapped"
            "UShort" -> "$name.toUShort() as $mapped"
            "UByte" -> "$name.toUByte() as $mapped"
            else -> name
        }
    }

    private fun isEnum(type: Type): Boolean = when {
        type is Type.Declared -> type.isEnum()
        type is Type.Delegated -> isEnum(type.type())
        else -> false
    }

    private fun isOptionsStyle(typeName: String): Boolean =
        typeName.endsWith("Options") || typeName.endsWith("Flags") || typeName.endsWith("Mask")

    private fun KotlinCallbackModel.rawParameters(): List<KotlinCallbackParameter> =
        (parameters + listOfNotNull(routingUserdataParameter)).sortedBy(KotlinCallbackParameter::index)
}
