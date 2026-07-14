package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.DeclarationImpl
import org.graphiks.kextract.Type
import org.graphiks.kextract.callbacks.AnalyzedCallback
import org.graphiks.kextract.callbacks.CallbackParameter
import org.graphiks.kextract.kotlin.utils.KotlinIdentifierAllocator

data class KotlinCallbackParameter(
    val index: Int,
    val name: String,
    val type: Type,
    val cAbiType: KotlinCallbackCAbiType,
)

data class KotlinCallbackModel(
    val canonicalId: String,
    val typeName: String,
    val runtimeTypeName: String,
    val trampolineName: String,
    val documentation: String?,
    val parameters: List<KotlinCallbackParameter>,
    val routingUserdataParameter: KotlinCallbackParameter?,
) {
    val hasRoutingUserdata: Boolean
        get() = routingUserdataParameter != null

    companion object {
        private val RESERVED_PARAMETER_NAMES = setOf(
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

        internal fun from(
            callback: AnalyzedCallback,
            names: KotlinIdentifierAllocator,
        ): KotlinCallbackModel {
            val typeName = names.allocate(callback.typedef.name(), "Callback")
            val parameterNames = KotlinIdentifierAllocator(RESERVED_PARAMETER_NAMES)
            val routingParameter = callback.routingUserdataParameter
            val routingIndex = routingParameter?.index
            fun modelParameter(parameter: CallbackParameter): KotlinCallbackParameter =
                KotlinCallbackParameter(
                    index = parameter.index,
                    name = parameterNames.allocate(parameter.name, "arg${parameter.index}"),
                    type = parameter.type,
                    cAbiType = KotlinCallbackCAbiType.from(parameter.type),
                )
            val rawParameters = callback.parameters.map(::modelParameter)
            return KotlinCallbackModel(
                canonicalId = callback.id,
                typeName = typeName,
                runtimeTypeName = names.allocate("${typeName}Type", "CallbackType"),
                trampolineName = names.allocate("${typeName}Trampoline", "CallbackTrampoline"),
                documentation = DeclarationImpl.SourceComment.get(callback.typedef)?.let(::normalizeDocumentation),
                parameters = rawParameters.filterNot { it.index == routingIndex },
                routingUserdataParameter = rawParameters.singleOrNull { it.index == routingIndex },
            )
        }

        private fun normalizeDocumentation(comment: DeclarationImpl.SourceComment): String? {
            val source = comment.raw.takeIf { it.isNotBlank() } ?: comment.brief
            return source.trim()
                .removePrefix("/**")
                .removePrefix("/*!")
                .removePrefix("/*")
                .removeSuffix("*/")
                .lines()
                .map { line ->
                    line.trim()
                        .removePrefix("///")
                        .removePrefix("//!")
                        .removePrefix("//")
                        .removePrefix("*")
                        .trim()
                }
                .dropWhile(String::isBlank)
                .dropLastWhile(String::isBlank)
                .joinToString("\n")
                .takeIf(String::isNotBlank)
        }
    }
}
