package org.graphiks.kextract.callbacks

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path

object CallbackBindingsLoader {
    private val mapper = ObjectMapper(YAMLFactory()).enable(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
    )

    fun load(path: Path): CallbackBindingsConfig = load(path.toFile().readText())

    fun load(yaml: String): CallbackBindingsConfig {
        val root = try {
            mapper.readTree(yaml)
        } catch (e: Exception) {
            throw CallbackBindingsException("callback-bindings: invalid YAML: ${e.message}", e)
        }

        validateTree(root)
        val config = try {
            mapper.treeToValue(root, CallbackBindingsConfig::class.java)
        } catch (e: Exception) {
            throw CallbackBindingsException("callback-bindings: invalid YAML schema: ${e.message}", e)
        }
        CallbackBindingsSchemaValidator.validate(config)
        return config
    }

    private fun validateTree(root: JsonNode) {
        if (!root.isObject) {
            throw CallbackBindingsException("callback-bindings: root must be a mapping")
        }
        rejectUnknown(root, setOf("directFunctionBindings", "callbackInfoBindings"), "callback-bindings")
        validateBindings(root, "directFunctionBindings", "function") { binding, id ->
            rejectUnknown(
                binding,
                setOf("function", "callbackParameter", "callbackType", "routingUserdataParameter"),
                id,
            )
            requireString(binding, "function", id)
            requireString(binding, "callbackParameter", id)
            requireString(binding, "callbackType", id)
            requireString(binding, "routingUserdataParameter", id, allowNull = true)
        }
        validateBindings(root, "callbackInfoBindings", "struct") { binding, id ->
            rejectUnknown(
                binding,
                setOf(
                    "struct",
                    "owner",
                    "callbackField",
                    "callbackType",
                    "routingUserdataField",
                    "applicationUserdataFields",
                    "mode",
                ),
                id,
            )
            requireString(binding, "struct", id)
            requireString(binding, "callbackField", id)
            requireString(binding, "callbackType", id)
            requireString(binding, "routingUserdataField", id)
            requireStringSequence(binding, "applicationUserdataFields", id)
            binding.get("owner")?.let { owner ->
                requireMapping(owner, id, "owner")
                rejectUnknown(owner, setOf("function", "parameterPath", "lifetime"), id)
                requireString(owner, "function", id, "owner.function")
                requireString(owner, "parameterPath", id, "owner.parameterPath")
                requireString(owner, "lifetime", id, "owner.lifetime")
                owner.get("lifetime")?.textValue()?.let { lifetime ->
                    if (lifetime != CallbackInfoLifetime.CONSUMED_DURING_CALL.name) {
                        throw CallbackBindingsException(
                            "$id: owner lifetime must be CONSUMED_DURING_CALL, found '$lifetime'",
                        )
                    }
                }
            }
            binding.get("mode")?.takeUnless(JsonNode::isNull)?.let { mode ->
                requireMapping(mode, id, "mode")
                rejectUnknown(mode, setOf("field", "type", "allowedConstants"), id)
                requireString(mode, "field", id, "mode.field")
                requireString(mode, "type", id, "mode.type")
                requireStringSequence(mode, "allowedConstants", id, "mode.allowedConstants")
            }
        }
    }

    private inline fun validateBindings(
        root: JsonNode,
        property: String,
        idProperty: String,
        validate: (JsonNode, String) -> Unit,
    ) {
        val bindings = root.get(property) ?: return
        requireSequence(bindings, "callback-bindings", property)
        bindings.forEachIndexed { index, binding ->
            val location = "$property[$index]"
            requireMapping(binding, location, "binding")
            val id = binding.get(idProperty)
                ?.takeIf(JsonNode::isTextual)
                ?.textValue()
                ?.takeIf(String::isNotBlank)
                ?: location
            validate(binding, id)
        }
    }

    private fun requireString(
        node: JsonNode,
        property: String,
        id: String,
        path: String = property,
        allowNull: Boolean = false,
    ) {
        val value = node.get(property) ?: return
        if (allowNull && value.isNull) return
        if (!value.isTextual) {
            throw CallbackBindingsException("$id: $path must be a string")
        }
    }

    private fun requireStringSequence(
        node: JsonNode,
        property: String,
        id: String,
        path: String = property,
    ) {
        val values = node.get(property) ?: return
        requireSequence(values, id, path)
        values.forEachIndexed { index, value ->
            if (!value.isTextual) {
                throw CallbackBindingsException("$id: $path[$index] must be a string")
            }
        }
    }

    private fun requireSequence(node: JsonNode, id: String, field: String) {
        if (!node.isArray) {
            throw CallbackBindingsException("$id: $field must be a sequence")
        }
    }

    private fun requireMapping(node: JsonNode, id: String, field: String) {
        if (!node.isObject) {
            throw CallbackBindingsException("$id: $field must be a mapping")
        }
    }

    private fun rejectUnknown(node: JsonNode, allowed: Set<String>, id: String) {
        if (!node.isObject) return
        val unknown = node.fieldNames().asSequence().firstOrNull { it !in allowed } ?: return
        throw CallbackBindingsException("$id: unknown YAML property '$unknown'")
    }
}
