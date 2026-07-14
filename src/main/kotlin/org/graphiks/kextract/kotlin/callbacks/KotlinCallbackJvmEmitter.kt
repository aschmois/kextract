package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.builders.SourceBuilder
import org.graphiks.kextract.pipeline.isEnum

class KotlinCallbackJvmEmitter(
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
        val descriptor = "FunctionDescriptor.ofVoid(" +
            rawParameters.joinToString(", ") { it.cAbiType.jvmLayout } +
            ")"

        builder.appendLine("@OptIn(CallbackRuntimeApi::class)")
        builder.appendLine("private object ${callback.trampolineName} {")
        builder.indent()
        builder.appendLine("private val descriptor: FunctionDescriptor = $descriptor")
        builder.appendLine("private val methodHandle: MethodHandle by lazy {")
        builder.indent()
        builder.appendLine("MethodHandles.lookup().findStatic(")
        builder.indent()
        builder.appendLine("${callback.trampolineName}::class.java,")
        builder.appendLine("\"invoke\",")
        builder.appendLine("descriptor.toMethodType(),")
        builder.unindent()
        builder.appendLine(")")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine("val address: NativeAddress by lazy {")
        builder.indent()
        builder.appendLine("NativeAddress(Linker.nativeLinker().upcallStub(methodHandle, descriptor, Arena.global()))")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
        builder.appendLine("@JvmStatic")
        builder.appendLine("private fun invoke(")
        builder.indent()
        rawParameters.forEach { parameter ->
            builder.appendLine("${parameter.name}: ${parameter.cAbiType.jvmCarrier},")
        }
        builder.unindent()
        builder.appendLine(") {")
        builder.indent()
        builder.appendLine("try {")
        builder.indent()
        builder.appendLine("CallbackRuntime.dispatchSafely(")
        builder.indent()
        builder.appendLine("type = ${callback.runtimeTypeName},")
        val routingUserdata = callback.routingUserdataParameter
            ?.name
            ?.let { "$it.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)" }
            ?: "null"
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
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    private fun emitInvocation(builder: SourceBuilder, callback: KotlinCallbackModel) {
        if (callback.parameters.size <= 1) {
            val arguments = callback.parameters.joinToString(", ") { adaptJvmArgument(it) }
            builder.appendLine("callback.invoke($arguments)")
            return
        }

        builder.appendLine("callback.invoke(")
        builder.indent()
        callback.parameters.forEach { parameter ->
            builder.appendLine("${adaptJvmArgument(parameter)},")
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
        builder.appendLine("trampoline = ${callback.trampolineName}.address,")
        builder.appendLine("policy = policy,")
        builder.appendLine("onError = onError,")
        builder.appendLine("callback = callback,")
        builder.unindent()
        builder.appendLine(")")
        builder.appendLine()
    }

    private fun adaptJvmArgument(parameter: KotlinCallbackParameter): String {
        val name = parameter.name
        val type = parameter.type
        val cAbiType = parameter.cAbiType
        val mapped = mapType(type)
        return when {
            isEnum(type) && isOptionsStyle(mapped) ->
                "$mapped(${optionsRawValue(name, cAbiType)})"
            isEnum(type) -> enumApplicationValue(name, mapped, cAbiType)
            mapped == "UInt" -> "$name.toUInt()"
            mapped == "ULong" -> "$name.toULong()"
            mapped == "UShort" -> "$name.toUShort()"
            mapped == "UByte" -> "$name.toUByte()"
            cAbiType is KotlinCallbackCAbiType.StructValue -> "$mapped(NativeAddress($name))"
            cAbiType is KotlinCallbackCAbiType.Address && mapped == "NativeAddress?" ->
                "$name.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)"
            cAbiType is KotlinCallbackCAbiType.Address && mapped == "CString?" ->
                "$name.takeIf { it != MemorySegment.NULL }?.let(::NativeAddress)?.let(::CString)"
            cAbiType is KotlinCallbackCAbiType.Address && mapped.endsWith("?") -> {
                val nonNullable = mapped.removeSuffix("?")
                if (cAbiType.pointerDepth > 1) {
                    "$name.takeIf { it != MemorySegment.NULL }" +
                        "?.reinterpret(ValueLayout.ADDRESS.byteSize())" +
                        "?.get(ValueLayout.ADDRESS, 0L)" +
                        "?.takeIf { it != MemorySegment.NULL }" +
                        "?.let(::NativeAddress)?.let { $nonNullable(it) }"
                } else {
                    "$name.takeIf { it != MemorySegment.NULL }" +
                        "?.let(::NativeAddress)?.let { $nonNullable(it) }"
                }
            }
            else -> name
        }
    }

    private fun optionsRawValue(name: String, cAbiType: KotlinCallbackCAbiType): String {
        val scalar = cAbiType as? KotlinCallbackCAbiType.Scalar
            ?: error("Options callback parameter must have a scalar C ABI type")
        if (scalar.jvmCarrier == "Long") return name
        if (!scalar.unsigned) return "$name.toLong()"
        return when (scalar.kind) {
            KotlinCallbackCAbiType.Scalar.Kind.I8 -> "$name.toUByte().toLong()"
            KotlinCallbackCAbiType.Scalar.Kind.I16 -> "$name.toUShort().toLong()"
            KotlinCallbackCAbiType.Scalar.Kind.I32 -> "$name.toUInt().toLong()"
            KotlinCallbackCAbiType.Scalar.Kind.CHAR16 -> "$name.code.toLong()"
            else -> "$name.toLong()"
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
