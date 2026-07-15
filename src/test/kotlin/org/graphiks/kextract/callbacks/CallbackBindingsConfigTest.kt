package org.graphiks.kextract.callbacks

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CallbackBindingsConfigTest {
    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `loads the strict typed callback schema from a file`() {
        val yaml = tempDir.resolve("callbacks.yml").also { it.writeText(VALID_YAML) }

        val config = CallbackBindingsLoader.load(yaml)

        assertEquals(1, config.directFunctionBindings.size)
        assertEquals("function:sampleSetCallback", config.directFunctionBindings.single().function)
        assertEquals("userdata2", config.directFunctionBindings.single().routingUserdataParameter)
        assertEquals(1, config.callbackInfoBindings.size)
        assertEquals("struct:SampleCallbackInfo", config.callbackInfoBindings.single().struct)
        assertEquals(
            CallbackInfoLifetime.CONSUMED_DURING_CALL,
            config.callbackInfoBindings.single().owner?.lifetime,
        )
        assertEquals(
            listOf("constant:SampleMode_Allow", "constant:SampleMode_Spontaneous"),
            config.callbackInfoBindings.single().mode?.allowedConstants,
        )
    }

    @Test
    fun `rejects an unknown YAML property with its canonical binding id`() {
        val yaml = VALID_YAML.replace(
            "    routingUserdataParameter: userdata2",
            "    routingUserdataParameter: userdata2\n    unexpected: true",
        )

        assertDiagnostic(
            "function:sampleSetCallback: unknown YAML property 'unexpected'",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects a duplicate canonical direct binding`() {
        val duplicate = """
            directFunctionBindings:
              - function: function:sampleSetCallback
                callbackParameter: callback
                callbackType: typedef:SampleCallback
                routingUserdataParameter: userdata2
              - function: function:sampleSetCallback
                callbackParameter: callback
                callbackType: typedef:SampleCallback
                routingUserdataParameter: userdata2
        """.trimIndent()

        assertDiagnostic(
            "function:sampleSetCallback: duplicate direct function binding",
        ) {
            CallbackBindingsLoader.load(duplicate)
        }
    }

    @Test
    fun `rejects empty canonical ids before AST validation`() {
        val yaml = """
            directFunctionBindings:
              - function: ""
                callbackParameter: callback
                callbackType: typedef:SampleCallback
        """.trimIndent()

        assertDiagnostic(
            "directFunctionBindings[0].function: canonical declaration ID must not be empty",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects a canonical id whose suffix is only whitespace`() {
        val yaml = """
            directFunctionBindings:
              - function: "function:   "
                callbackParameter: callback
                callbackType: typedef:SampleCallback
        """.trimIndent()

        assertDiagnostic(
            "directFunctionBindings[0].function: canonical declaration ID must not be empty",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects a canonical id with the wrong declaration kind`() {
        val yaml = """
            directFunctionBindings:
              - function: typedef:SampleCallback
                callbackParameter: callback
                callbackType: typedef:SampleCallback
        """.trimIndent()

        assertDiagnostic(
            "typedef:SampleCallback: expected canonical function ID with prefix 'function:'",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects an unsupported callback-info lifetime with its canonical struct id`() {
        val yaml = VALID_YAML.replace("CONSUMED_DURING_CALL", "RETAINED_BY_NATIVE")

        assertDiagnostic(
            "struct:SampleCallbackInfo: owner lifetime must be CONSUMED_DURING_CALL, " +
                "found 'RETAINED_BY_NATIVE'",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects malformed nested owner using the extractible struct id`() {
        listOf("not-a-mapping", "null").forEach { owner ->
            val yaml = """
                callbackInfoBindings:
                  - struct: struct:SampleCallbackInfo
                    owner: $owner
                    callbackField: callback
                    callbackType: typedef:SampleCallback
                    routingUserdataField: userdata2
            """.trimIndent()

            assertDiagnostic("struct:SampleCallbackInfo: owner must be a mapping") {
                CallbackBindingsLoader.load(yaml)
            }
        }
    }

    @Test
    fun `rejects malformed nested mode using the extractible struct id`() {
        val yaml = """
            callbackInfoBindings:
              - struct: struct:SampleCallbackInfo
                owner:
                  function: function:sampleOwnCallbackInfo
                  parameterPath: callbackInfo
                  lifetime: CONSUMED_DURING_CALL
                callbackField: callback
                callbackType: typedef:SampleCallback
                routingUserdataField: userdata2
                mode: []
        """.trimIndent()

        assertDiagnostic("struct:SampleCallbackInfo: mode must be a mapping") {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects non-sequence binding collections before Jackson conversion`() {
        listOf("directFunctionBindings", "callbackInfoBindings").forEach { property ->
            assertDiagnostic("callback-bindings: $property must be a sequence") {
                CallbackBindingsLoader.load("$property: {}")
            }
        }
    }

    @Test
    fun `rejects non-mapping binding items with their indexed location`() {
        listOf("directFunctionBindings", "callbackInfoBindings").forEach { property ->
            assertDiagnostic("$property[0]: binding must be a mapping") {
                CallbackBindingsLoader.load(
                    """
                        $property:
                          - malformed
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `rejects non-string direct and callback-info properties with the binding id`() {
        val cases = listOf(
            VALID_YAML.replace(
                "    callbackParameter: callback",
                "    callbackParameter: [callback]",
            ) to "function:sampleSetCallback: callbackParameter must be a string",
            VALID_YAML.replace(
                "    callbackField: callback",
                "    callbackField: { name: callback }",
            ) to "struct:SampleCallbackInfo: callbackField must be a string",
        )

        cases.forEach { (yaml, diagnostic) ->
            assertDiagnostic(diagnostic) { CallbackBindingsLoader.load(yaml) }
        }
    }

    @Test
    fun `rejects non-string owner and mode properties with the struct id`() {
        val cases = listOf(
            VALID_YAML.replace(
                "      parameterPath: callbackInfo",
                "      parameterPath: [callbackInfo]",
            ) to "struct:SampleCallbackInfo: owner.parameterPath must be a string",
            VALID_YAML.replace(
                "      field: mode",
                "      field: 42",
            ) to "struct:SampleCallbackInfo: mode.field must be a string",
        )

        cases.forEach { (yaml, diagnostic) ->
            assertDiagnostic(diagnostic) { CallbackBindingsLoader.load(yaml) }
        }
    }

    @Test
    fun `rejects non-sequence application userdata fields with the struct id`() {
        val yaml = VALID_YAML.replace(
            "    applicationUserdataFields: [userdata1]",
            "    applicationUserdataFields: userdata1",
        )

        assertDiagnostic(
            "struct:SampleCallbackInfo: applicationUserdataFields must be a sequence",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects non-string application userdata list items with the struct id`() {
        val yaml = VALID_YAML.replace(
            "    applicationUserdataFields: [userdata1]",
            "    applicationUserdataFields: [userdata1, 7]",
        )

        assertDiagnostic(
            "struct:SampleCallbackInfo: applicationUserdataFields[1] must be a string",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects non-sequence allowed constants with the struct id`() {
        val yaml = VALID_YAML.replace(
            "      allowedConstants:\n" +
                "        - constant:SampleMode_Allow\n" +
                "        - constant:SampleMode_Spontaneous",
            "      allowedConstants: constant:SampleMode_Allow",
        )

        assertDiagnostic(
            "struct:SampleCallbackInfo: mode.allowedConstants must be a sequence",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    @Test
    fun `rejects non-string allowed constant list items with the struct id`() {
        val yaml = VALID_YAML.replace(
            "        - constant:SampleMode_Spontaneous",
            "        - 7",
        )

        assertDiagnostic(
            "struct:SampleCallbackInfo: mode.allowedConstants[1] must be a string",
        ) {
            CallbackBindingsLoader.load(yaml)
        }
    }

    private fun assertDiagnostic(expected: String, block: () -> Unit) {
        val failure = assertFailsWith<CallbackBindingsException>(block = block)
        assertEquals(expected, failure.message)
    }

    private companion object {
        val VALID_YAML = """
            directFunctionBindings:
              - function: function:sampleSetCallback
                callbackParameter: callback
                callbackType: typedef:SampleCallback
                routingUserdataParameter: userdata2
            callbackInfoBindings:
              - struct: struct:SampleCallbackInfo
                owner:
                  function: function:sampleOwnCallbackInfo
                  parameterPath: callbackInfo
                  lifetime: CONSUMED_DURING_CALL
                callbackField: callback
                callbackType: typedef:SampleCallback
                routingUserdataField: userdata2
                applicationUserdataFields: [userdata1]
                mode:
                  field: mode
                  type: typedef:SampleMode
                  allowedConstants:
                    - constant:SampleMode_Allow
                    - constant:SampleMode_Spontaneous
        """.trimIndent()
    }
}
