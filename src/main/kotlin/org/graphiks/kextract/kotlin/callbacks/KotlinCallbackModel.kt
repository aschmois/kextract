package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.DeclarationImpl
import org.graphiks.kextract.Type
import org.graphiks.kextract.callbacks.AnalyzedCallback

data class KotlinCallbackParameter(
    val name: String,
    val type: Type,
)

data class KotlinCallbackModel(
    val canonicalId: String,
    val typeName: String,
    val documentation: String?,
    val parameters: List<KotlinCallbackParameter>,
    val hasRoutingUserdata: Boolean,
) {
    companion object {
        fun from(callback: AnalyzedCallback): KotlinCallbackModel {
            val routingIndex = callback.routingUserdataParameter?.index
            return KotlinCallbackModel(
                canonicalId = callback.id,
                typeName = callback.typedef.name(),
                documentation = DeclarationImpl.SourceComment.get(callback.typedef)?.let(::normalizeDocumentation),
                parameters = callback.parameters
                    .filterNot { it.index == routingIndex }
                    .map { parameter ->
                        KotlinCallbackParameter(
                            name = parameter.name.takeIf(String::isNotEmpty) ?: "arg${parameter.index}",
                            type = parameter.type,
                        )
                    },
                hasRoutingUserdata = routingIndex != null,
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
