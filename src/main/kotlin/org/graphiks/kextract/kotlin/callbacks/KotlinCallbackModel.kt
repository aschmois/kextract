package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.DeclarationImpl
import org.graphiks.kextract.Type
import org.graphiks.kextract.callbacks.AnalyzedCallback
import org.graphiks.kextract.callbacks.CallbackParameter

data class KotlinCallbackParameter(
    val index: Int,
    val name: String,
    val type: Type,
    val cAbiType: KotlinCallbackCAbiType,
)

data class KotlinCallbackModel(
    val canonicalId: String,
    val typeName: String,
    val documentation: String?,
    val parameters: List<KotlinCallbackParameter>,
    val routingUserdataParameter: KotlinCallbackParameter?,
) {
    val hasRoutingUserdata: Boolean
        get() = routingUserdataParameter != null

    companion object {
        fun from(callback: AnalyzedCallback): KotlinCallbackModel {
            val routingParameter = callback.routingUserdataParameter
            val routingIndex = routingParameter?.index
            fun modelParameter(parameter: CallbackParameter): KotlinCallbackParameter =
                KotlinCallbackParameter(
                    index = parameter.index,
                    name = parameter.name.takeIf(String::isNotEmpty) ?: "arg${parameter.index}",
                    type = parameter.type,
                    cAbiType = KotlinCallbackCAbiType.from(parameter.type),
                )
            return KotlinCallbackModel(
                canonicalId = callback.id,
                typeName = callback.typedef.name(),
                documentation = DeclarationImpl.SourceComment.get(callback.typedef)?.let(::normalizeDocumentation),
                parameters = callback.parameters
                    .filterNot { it.index == routingIndex }
                    .map(::modelParameter),
                routingUserdataParameter = routingParameter?.let(::modelParameter),
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
