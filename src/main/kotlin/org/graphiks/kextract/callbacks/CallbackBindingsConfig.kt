package org.graphiks.kextract.callbacks

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type

/** Mutable, Jackson-friendly callback binding configuration. */
class CallbackBindingsConfig {
    var directFunctionBindings: List<DirectFunctionBinding> = emptyList()
    var callbackInfoBindings: List<CallbackInfoBinding> = emptyList()
}

class DirectFunctionBinding {
    var function: String = ""
    var callbackParameter: String = ""
    var callbackType: String = ""
    var routingUserdataParameter: String? = null
}

class CallbackInfoBinding {
    var struct: String = ""
    var owner: CallbackInfoOwner? = null
    var callbackField: String = ""
    var callbackType: String = ""
    var routingUserdataField: String = ""
    var applicationUserdataFields: List<String> = emptyList()
    var mode: CallbackInfoMode? = null
}

class CallbackInfoOwner {
    var function: String = ""
    var parameterPath: String = ""
    var lifetime: CallbackInfoLifetime? = null
}

class CallbackInfoMode {
    var field: String = ""
    var type: String = ""
    var allowedConstants: List<String> = emptyList()
}

enum class CallbackInfoLifetime { CONSUMED_DURING_CALL }

class CallbackBindingsException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

data class CallbackParameter(
    val index: Int,
    val name: String,
    val type: Type,
)

data class AnalyzedCallback(
    val id: String,
    val typedef: Declaration.Typedef,
    val functionType: Type.Function,
    val parameters: List<CallbackParameter>,
    val applicationUserdataParameters: List<CallbackParameter>,
    val routingUserdataParameter: CallbackParameter?,
)

data class ValidatedDirectFunctionBinding(
    val function: Declaration.Function,
    val callbackParameter: Declaration.Variable,
    val callback: AnalyzedCallback,
    val routingUserdataParameter: Declaration.Variable?,
)

data class ValidatedCallbackInfoOwner(
    val function: Declaration.Function,
    val parameterPath: List<Declaration.Variable>,
    val lifetime: CallbackInfoLifetime,
)

data class ValidatedCallbackInfoMode(
    val field: Declaration.Variable,
    val type: CanonicalTypedef,
    val allowedConstants: List<Declaration.Constant>,
)

data class ValidatedCallbackInfoBinding(
    val struct: Declaration.Scoped,
    val owner: ValidatedCallbackInfoOwner,
    val callbackField: Declaration.Variable,
    val callback: AnalyzedCallback,
    val routingUserdataField: Declaration.Variable,
    val applicationUserdataFields: List<Declaration.Variable>,
    val mode: ValidatedCallbackInfoMode?,
)

data class ValidatedCallbackBindings(
    val callbacks: List<AnalyzedCallback> = emptyList(),
    val directFunctionBindings: List<ValidatedDirectFunctionBinding> = emptyList(),
    val callbackInfoBindings: List<ValidatedCallbackInfoBinding> = emptyList(),
) {
    companion object {
        val EMPTY = ValidatedCallbackBindings()
    }
}

internal object CallbackBindingsSchemaValidator {
    fun validate(config: CallbackBindingsConfig) {
        config.directFunctionBindings.forEachIndexed { index, binding ->
            requireCanonicalId(binding.function, "directFunctionBindings[$index].function", "function")
            requireValue(binding.callbackParameter, binding.function, "callbackParameter")
            requireCanonicalId(binding.callbackType, binding.function, "typedef")
            binding.routingUserdataParameter?.let {
                requireValue(it, binding.function, "routingUserdataParameter")
            }
        }
        config.callbackInfoBindings.forEachIndexed { index, binding ->
            requireCanonicalId(binding.struct, "callbackInfoBindings[$index].struct", "struct")
            val owner = binding.owner
                ?: throw CallbackBindingsException("${binding.struct}: owner is required")
            requireCanonicalId(owner.function, binding.struct, "function")
            requireValue(owner.parameterPath, binding.struct, "owner parameterPath")
            if (owner.parameterPath.split('.').any(String::isEmpty)) {
                throw CallbackBindingsException(
                    "${binding.struct}: owner parameterPath '${owner.parameterPath}' contains an empty segment",
                )
            }
            if (owner.lifetime != CallbackInfoLifetime.CONSUMED_DURING_CALL) {
                throw CallbackBindingsException(
                    "${binding.struct}: owner lifetime must be CONSUMED_DURING_CALL",
                )
            }
            requireValue(binding.callbackField, binding.struct, "callbackField")
            requireCanonicalId(binding.callbackType, binding.struct, "typedef")
            requireValue(binding.routingUserdataField, binding.struct, "routingUserdataField")
            binding.applicationUserdataFields.forEachIndexed { fieldIndex, field ->
                requireValue(field, binding.struct, "applicationUserdataFields[$fieldIndex]")
            }
            binding.mode?.let { mode ->
                requireValue(mode.field, binding.struct, "mode field")
                requireCanonicalId(mode.type, binding.struct, "typedef")
                mode.allowedConstants.forEach {
                    requireCanonicalId(it, binding.struct, "constant")
                }
            }
        }

        duplicate(config.directFunctionBindings.map { it.function })?.let {
            throw CallbackBindingsException("$it: duplicate direct function binding")
        }
        duplicate(config.callbackInfoBindings.map { it.struct })?.let {
            throw CallbackBindingsException("$it: duplicate callback-info binding")
        }
    }

    private fun requireCanonicalId(value: String, location: String, kind: String) {
        if (value.isBlank()) {
            throw CallbackBindingsException("$location: canonical declaration ID must not be empty")
        }
        val prefix = "$kind:"
        if (!value.startsWith(prefix)) {
            throw CallbackBindingsException(
                "$value: expected canonical $kind ID with prefix '$kind:'",
            )
        }
        if (value.removePrefix(prefix).isBlank()) {
            throw CallbackBindingsException("$location: canonical declaration ID must not be empty")
        }
    }

    private fun requireValue(value: String, id: String, field: String) {
        if (value.isBlank()) {
            throw CallbackBindingsException("$id: $field is required")
        }
    }

    private fun duplicate(ids: List<String>): String? =
        ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
}
