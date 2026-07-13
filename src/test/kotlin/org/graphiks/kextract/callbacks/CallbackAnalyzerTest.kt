package org.graphiks.kextract.callbacks

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Position
import org.graphiks.kextract.Type
import org.graphiks.kextract.pipeline.DuplicateFilter
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.Logger
import org.graphiks.kextract.pipeline.Options
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackAnalyzerTest {
    @TempDir
    private lateinit var tempDir: Path

    private lateinit var declarations: Declaration.Scoped
    private lateinit var index: CanonicalDeclarationIndex

    @BeforeEach
    fun parseFixture() {
        val header = tempDir.resolve("callbacks.h").also { it.writeText(FIXTURE_HEADER) }
        declarations = KextractTool.parse(listOf(header.toString()))
        index = CanonicalDeclarationIndex(declarations)
    }

    @Test
    fun `validates direct and callback-info metadata against canonical declarations`() {
        val validated = CallbackAnalyzer.validate(index, validConfig())

        val direct = validated.directFunctionBindings.single()
        assertEquals("sampleSetCallback", direct.function.name())
        assertEquals("callback", direct.callbackParameter.name())
        assertEquals("userdata2", direct.routingUserdataParameter?.name())
        assertEquals("userdata2", direct.callback.routingUserdataParameter?.name)
        assertEquals(listOf("userdata1"), direct.callback.applicationUserdataParameters.map { it.name })

        val info = validated.callbackInfoBindings.single()
        assertEquals("SampleCallbackInfo", info.struct.name())
        assertEquals(listOf("callbackInfo"), info.owner.parameterPath.map { it.name() })
        assertEquals("callback", info.callbackField.name())
        assertEquals("userdata2", info.routingUserdataField.name())
        assertEquals(listOf("userdata1"), info.applicationUserdataFields.map { it.name() })
        assertEquals(
            listOf("SampleMode_Allow", "SampleMode_Spontaneous"),
            info.mode?.allowedConstants?.map { it.name() },
        )
    }

    @Test
    fun `emits callback-info factories from validated mode lifetime and userdata metadata`() {
        val common = generateCommon(validConfig(), "callback-info")

        val factory = common
            .substringAfter("fun SampleCallbackInfo.Companion.allocate(\n")
            .substringBefore("\n}\n")
        assertTrue(
            factory.startsWith(
                "    allocator: MemoryAllocator,\n" +
                    "    mode: SampleMode,\n" +
                    "    registration: CallbackRegistration<SampleCallback>,\n" +
                    "    userdata1: NativeAddress? = null,\n" +
                    "): SampleCallbackInfo {",
            ),
        )
        assertTrue(factory.contains("mode == SampleMode_Allow ||"))
        assertTrue(factory.contains("mode == SampleMode_Spontaneous,"))
        assertTrue(factory.indexOf("require(") < factory.indexOf("val info = allocate(allocator)"))
        assertTrue(factory.contains("info.callback = registration.callback"))
        assertTrue(factory.contains("info.userdata2 = registration.userdata"))
        assertTrue(factory.contains("info.userdata1 = userdata1"))
        assertTrue(factory.contains("return info"))
        assertTrue(common.contains("CONSUMED_DURING_CALL: the owning native call copies the callback-info value or containing descriptor, so the allocator scope may close after the call while the registration remains live."))
        assertTrue(!factory.contains("registration.close()"))

        val noModeConfig = validConfig().also { it.callbackInfoBindings.single().mode = null }
        val noModeCommon = generateCommon(noModeConfig, "callback-info-no-mode")
        val noModeFactory = noModeCommon
            .substringAfter("fun SampleCallbackInfo.Companion.allocate(\n")
            .substringBefore("\n}\n")
        assertTrue(!noModeFactory.contains("mode: SampleMode"))
        assertTrue(!noModeFactory.contains("info.mode ="))
    }

    @Test
    fun `accepts a structurally valid callback without userdata`() {
        val callback = CallbackAnalyzer.analyzeCallback(
            "typedef:NoUserdataCallback",
            index.requireTypedef("typedef:NoUserdataCallback"),
        )

        assertNull(callback.routingUserdataParameter)
        assertEquals(emptyList(), callback.applicationUserdataParameters)
    }

    @Test
    fun `discovers every surviving function-pointer typedef with empty config`() {
        val automaticIndex = parseIndex(
            """
                typedef void (*AutoHandler)(int value);
                typedef void (*AnotherRegistration)(void);
            """.trimIndent(),
        )

        val validated = CallbackAnalyzer.validate(automaticIndex, CallbackBindingsConfig())

        assertEquals(
            listOf("typedef:AutoHandler", "typedef:AnotherRegistration"),
            validated.callbacks.map { it.id },
        )
    }

    @Test
    fun `accepts a const-qualified opaque userdata pointer`() {
        val automaticIndex = parseIndex(
            "typedef void (*ConstOpaqueHandler)(void const * userdata);",
        )

        val validated = CallbackAnalyzer.validate(automaticIndex, CallbackBindingsConfig())

        assertEquals(
            "userdata",
            validated.callbacks.single().routingUserdataParameter?.name,
        )
    }

    @Test
    fun `rejects an invalid discovered callback without configuration`() {
        val automaticIndex = parseIndex("typedef int (*InvalidHandler)(void * userdata);")

        assertDiagnostic(
            "typedef:InvalidHandler: callback return type must be void, found int",
        ) {
            CallbackAnalyzer.validate(automaticIndex, CallbackBindingsConfig())
        }
    }

    @Test
    fun `rejects callback returning pointer to void`() {
        val automaticIndex = parseIndex("typedef void * (*PointerReturnHandler)(void);")

        assertDiagnostic(
            "typedef:PointerReturnHandler: callback return type must be void, found void *",
        ) {
            CallbackAnalyzer.validate(automaticIndex, CallbackBindingsConfig())
        }
    }

    @Test
    fun `rejects a function typedef without the required pointer`() {
        val automaticIndex = parseIndex("typedef void DirectFunctionType(int value);")

        assertDiagnostic(
            "typedef:DirectFunctionType: callback typedef must contain exactly one pointer to a function",
        ) {
            CallbackAnalyzer.analyzeCallback(
                "typedef:DirectFunctionType",
                automaticIndex.requireTypedef("typedef:DirectFunctionType"),
            )
        }
    }

    @Test
    fun `rejects a callback typedef with multiple pointer levels`() {
        val automaticIndex = parseIndex("typedef void (**DoublePointerHandler)(int value);")

        assertDiagnostic(
            "typedef:DoublePointerHandler: callback typedef must contain exactly one pointer to a function",
        ) {
            CallbackAnalyzer.analyzeCallback(
                "typedef:DoublePointerHandler",
                automaticIndex.requireTypedef("typedef:DoublePointerHandler"),
            )
        }
    }

    @Test
    fun `rejects userdata and userdata0 as an ambiguous normalized slot`() {
        val automaticIndex = parseIndex(
            "typedef void (*AmbiguousSlots)(void * userdata, void * userdata0);",
        )

        assertDiagnostic(
            "typedef:AmbiguousSlots: ambiguous userdata parameters 'userdata', 'userdata0' normalize to index 0",
        ) {
            CallbackAnalyzer.validate(automaticIndex, CallbackBindingsConfig())
        }
    }

    @Test
    fun `reserves the greatest userdata suffix regardless of argument order`() {
        val automaticIndex = parseIndex(
            "typedef void (*OrderedSlots)(void * userdata9, void * userdata2, int value);",
        )

        val callback = CallbackAnalyzer.validate(automaticIndex, CallbackBindingsConfig()).callbacks.single()

        assertEquals("userdata9", callback.routingUserdataParameter?.name)
        assertEquals(listOf("userdata2"), callback.applicationUserdataParameters.map { it.name })
    }

    @Test
    fun `unwraps userdata typedef aliases for structural compatibility`() {
        val userdataAlias = Type.typedef("OpaqueUserdata", Type.pointer(Type.void_()))
        val callbackFunction = Type.function(false, Type.void_(), userdataAlias)
            .withParameterNames(listOf("userdata"))
        val callbackTypedef = Declaration.typedef(
            Position.NO_POSITION,
            "AliasedUserdataCallback",
            Type.pointer(callbackFunction),
        )
        val callbackParameterType = Type.typedef("AliasedUserdataCallback", callbackTypedef.type())
        val setter = Declaration.function(
            Position.NO_POSITION,
            "setAliasedUserdataCallback",
            Type.function(false, Type.void_(), callbackParameterType, Type.pointer(Type.void_())),
            Declaration.parameter(Position.NO_POSITION, "callback", callbackParameterType),
            Declaration.parameter(Position.NO_POSITION, "userdata", Type.pointer(Type.void_())),
        )
        val programmaticIndex = programmaticIndex(callbackTypedef, setter)

        val validated = CallbackAnalyzer.validate(
            programmaticIndex,
            directConfig(
                "function:setAliasedUserdataCallback",
                "typedef:AliasedUserdataCallback",
                "userdata",
            ),
        )

        assertEquals("userdata", validated.directFunctionBindings.single().routingUserdataParameter?.name())
    }

    @Test
    fun `unwraps qualifiers around opaque userdata pointers`() {
        val qualifiedPointer = Type.qualified(
            Type.Delegated.Kind.ATOMIC,
            Type.qualified(Type.Delegated.Kind.VOLATILE, Type.pointer(Type.void_())),
        )
        val callbackFunction = Type.function(false, Type.void_(), qualifiedPointer)
            .withParameterNames(listOf("userdata"))
        val callbackTypedef = Declaration.typedef(
            Position.NO_POSITION,
            "QualifiedUserdataCallback",
            Type.pointer(callbackFunction),
        )
        val callbackParameterType = Type.typedef("QualifiedUserdataCallback", callbackTypedef.type())
        val setter = Declaration.function(
            Position.NO_POSITION,
            "setQualifiedUserdataCallback",
            Type.function(false, Type.void_(), callbackParameterType, Type.pointer(Type.void_())),
            Declaration.parameter(Position.NO_POSITION, "callback", callbackParameterType),
            Declaration.parameter(Position.NO_POSITION, "userdata", Type.pointer(Type.void_())),
        )
        val programmaticIndex = programmaticIndex(callbackTypedef, setter)

        val validated = CallbackAnalyzer.validate(
            programmaticIndex,
            directConfig(
                "function:setQualifiedUserdataCallback",
                "typedef:QualifiedUserdataCallback",
                "userdata",
            ),
        )

        assertEquals("userdata", validated.directFunctionBindings.single().routingUserdataParameter?.name())
    }

    @Test
    fun `accepts mode constants through a legitimate nominal typedef alias chain`() {
        val uint = Type.qualified(Type.Delegated.Kind.UNSIGNED, Type.primitive(Type.Primitive.Kind.Int))
        val baseMode = Declaration.typedef(Position.NO_POSITION, "BaseMode", uint)
        val aliasMode = Declaration.typedef(Position.NO_POSITION, "AliasMode", Type.typedef("BaseMode", uint))
        val callbackFunction = Type.function(false, Type.void_(), Type.pointer(Type.void_()))
            .withParameterNames(listOf("userdata"))
        val callbackTypedef = Declaration.typedef(
            Position.NO_POSITION,
            "ModeCallback",
            Type.pointer(callbackFunction),
        )
        val info = Declaration.struct(
            Position.NO_POSITION,
            "ModeCallbackInfo",
            Declaration.field(
                Position.NO_POSITION,
                "mode",
                Type.typedef("AliasMode", Type.typedef("BaseMode", uint)),
            ),
            Declaration.field(
                Position.NO_POSITION,
                "callback",
                Type.typedef("ModeCallback", callbackTypedef.type()),
            ),
            Declaration.field(Position.NO_POSITION, "userdata", Type.pointer(Type.void_())),
        )
        val owner = Declaration.function(
            Position.NO_POSITION,
            "ownModeInfo",
            Type.function(false, Type.void_(), Type.declared(info)),
            Declaration.parameter(Position.NO_POSITION, "info", Type.declared(info)),
        )
        val allowed = Declaration.constant(
            Position.NO_POSITION,
            "BaseMode_Allow",
            1,
            Type.typedef("BaseMode", uint),
        )
        val config = CallbackBindingsConfig().also {
            it.callbackInfoBindings = listOf(
                CallbackInfoBinding().also { binding ->
                    binding.struct = "struct:ModeCallbackInfo"
                    binding.owner = CallbackInfoOwner().also { ownerConfig ->
                        ownerConfig.function = "function:ownModeInfo"
                        ownerConfig.parameterPath = "info"
                        ownerConfig.lifetime = CallbackInfoLifetime.CONSUMED_DURING_CALL
                    }
                    binding.callbackField = "callback"
                    binding.callbackType = "typedef:ModeCallback"
                    binding.routingUserdataField = "userdata"
                    binding.mode = CallbackInfoMode().also { mode ->
                        mode.field = "mode"
                        mode.type = "typedef:AliasMode"
                        mode.allowedConstants = listOf("constant:BaseMode_Allow")
                    }
                },
            )
        }

        val validated = CallbackAnalyzer.validate(
            programmaticIndex(baseMode, aliasMode, callbackTypedef, info, owner, allowed),
            config,
        )

        assertEquals("BaseMode_Allow", validated.callbackInfoBindings.single().mode?.allowedConstants?.single()?.name())
    }

    @Test
    fun `applies canonical id schema validation to programmatic callers`() {
        val config = validConfig().also {
            it.directFunctionBindings.single().function = "typedef:SampleCallback"
        }

        assertDiagnostic(
            "typedef:SampleCallback: expected canonical function ID with prefix 'function:'",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects owner paths containing empty segments`() {
        listOf(".callbackInfo", "callbackInfo..field", "callbackInfo.").forEach { path ->
            val config = validConfig().also {
                it.callbackInfoBindings.single().owner?.parameterPath = path
            }
            assertDiagnostic(
                "struct:SampleCallbackInfo: owner parameterPath '$path' contains an empty segment",
            ) {
                CallbackAnalyzer.validate(index, config)
            }
        }
    }

    @Test
    fun `resolves a valid nested owner path exactly`() {
        val config = validConfig().also {
            it.callbackInfoBindings.single().owner?.function = "function:sampleOwnNestedCallbackInfo"
            it.callbackInfoBindings.single().owner?.parameterPath = "descriptor.info"
        }

        val validated = CallbackAnalyzer.validate(index, config)

        assertEquals(
            listOf("descriptor", "info"),
            validated.callbackInfoBindings.single().owner.parameterPath.map { it.name() },
        )
    }

    @Test
    fun `rejects a userdata field whose type differs from the callback slot`() {
        val wrongIndex = parseIndex(
            """
                typedef void (*WrongFieldCallback)(void * userdata);
                typedef struct WrongFieldInfo {
                    WrongFieldCallback callback;
                    int userdata;
                } WrongFieldInfo;
                void ownWrongFieldInfo(WrongFieldInfo info);
            """.trimIndent(),
        )
        val config = CallbackBindingsConfig().also {
            it.callbackInfoBindings = listOf(
                CallbackInfoBinding().also { binding ->
                    binding.struct = "struct:WrongFieldInfo"
                    binding.owner = CallbackInfoOwner().also { owner ->
                        owner.function = "function:ownWrongFieldInfo"
                        owner.parameterPath = "info"
                        owner.lifetime = CallbackInfoLifetime.CONSUMED_DURING_CALL
                    }
                    binding.callbackField = "callback"
                    binding.callbackType = "typedef:WrongFieldCallback"
                    binding.routingUserdataField = "userdata"
                },
            )
        }

        assertDiagnostic(
            "struct:WrongFieldInfo: userdata field 'userdata' has non-opaque-pointer type int",
        ) {
            CallbackAnalyzer.validate(wrongIndex, config)
        }
    }

    @Test
    fun `ignores skipped duplicate typedefs during automatic discovery`() {
        val function = Type.function(false, Type.void_())
        val first = Declaration.typedef(Position.NO_POSITION, "DuplicateHandler", Type.pointer(function))
        val skipped = Declaration.typedef(Position.NO_POSITION, "DuplicateHandler", Type.pointer(function))
        val filteredHeader = DuplicateFilter().scan(
            Declaration.toplevel(Position.NO_POSITION, first, skipped),
        )

        val validated = CallbackAnalyzer.validate(
            CanonicalDeclarationIndex(filteredHeader),
            CallbackBindingsConfig(),
        )

        assertEquals(listOf("typedef:DuplicateHandler"), validated.callbacks.map { it.id })
    }

    @Test
    fun `rejects multiple surviving declarations for one canonical typedef id`() {
        val function = Type.function(false, Type.void_())
        val first = Declaration.typedef(Position.NO_POSITION, "DuplicateHandler", Type.pointer(function))
        val second = Declaration.typedef(Position.NO_POSITION, "DuplicateHandler", Type.pointer(function))

        assertDiagnostic(
            "typedef:DuplicateHandler: canonical declaration resolves 2 times; expected exactly once",
        ) {
            CallbackAnalyzer.validate(
                programmaticIndex(first, second),
                CallbackBindingsConfig(),
            )
        }
    }

    @Test
    fun `validated model is unaffected by later mutable config changes`() {
        val config = validConfig()
        val validated = CallbackAnalyzer.validate(index, config)

        config.directFunctionBindings.single().function = "function:mutated"
        config.callbackInfoBindings.single().routingUserdataField = "mutated"
        config.callbackInfoBindings.single().owner?.parameterPath = "mutated"

        assertEquals("sampleSetCallback", validated.directFunctionBindings.single().function.name())
        assertEquals("userdata2", validated.callbackInfoBindings.single().routingUserdataField.name())
        assertEquals(
            listOf("callbackInfo"),
            validated.callbackInfoBindings.single().owner.parameterPath.map { it.name() },
        )
    }

    @Test
    fun `rejects callback-info metadata without an owner`() {
        val config = validConfig().also { it.callbackInfoBindings.single().owner = null }

        assertDiagnostic("struct:SampleCallbackInfo: owner is required") {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects callback-info metadata without the consumed-during-call lifetime`() {
        val config = validConfig().also { it.callbackInfoBindings.single().owner?.lifetime = null }

        assertDiagnostic(
            "struct:SampleCallbackInfo: owner lifetime must be CONSUMED_DURING_CALL",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects a stale canonical id`() {
        val config = validConfig().also {
            it.directFunctionBindings.single().function = "function:removedSetter"
        }

        assertDiagnostic(
            "function:removedSetter: canonical declaration does not exist",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects a callback typedef mismatch`() {
        val config = validConfig().also {
            it.directFunctionBindings.single().callbackType = "typedef:NoUserdataCallback"
        }

        assertDiagnostic(
            "function:sampleSetCallback: callback parameter 'callback' has type " +
                "typedef:SampleCallback, not typedef:NoUserdataCallback",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects routing and application userdata overlap`() {
        val config = validConfig().also {
            it.callbackInfoBindings.single().applicationUserdataFields = listOf("userdata2")
        }

        assertDiagnostic(
            "struct:SampleCallbackInfo: routing userdata field 'userdata2' overlaps application userdata fields",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects a routing field that is not the last compatible callback userdata`() {
        val config = validConfig().also {
            it.callbackInfoBindings.single().routingUserdataField = "userdata1"
            it.callbackInfoBindings.single().applicationUserdataFields = listOf("userdata2")
        }

        assertDiagnostic(
            "struct:SampleCallbackInfo: routing userdata field 'userdata1' does not match " +
                "the reserved callback parameter 'userdata2'",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects an invalid mode field`() {
        val config = validConfig().also { it.callbackInfoBindings.single().mode?.field = "missingMode" }

        assertDiagnostic(
            "struct:SampleCallbackInfo: mode field 'missingMode' does not exist",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects an invalid mode type`() {
        val config = validConfig().also {
            it.callbackInfoBindings.single().mode?.type = "typedef:OtherMode"
        }

        assertDiagnostic(
            "struct:SampleCallbackInfo: mode field 'mode' has type typedef:SampleMode, not typedef:OtherMode",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects a mode constant from a different type`() {
        val config = validConfig().also {
            it.callbackInfoBindings.single().mode?.allowedConstants = listOf("constant:OtherMode_Allow")
        }

        assertDiagnostic(
            "constant:OtherMode_Allow: constant type typedef:OtherMode does not match typedef:SampleMode " +
                "for struct:SampleCallbackInfo",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    @Test
    fun `rejects a non-void callback even when configured`() {
        val invalidIndex = parseIndex(
            """
                typedef int (*NonVoidCallback)(void * userdata);
                void sampleSetNonVoidCallback(NonVoidCallback callback, void * userdata);
            """.trimIndent(),
        )
        val config = directConfig(
            function = "function:sampleSetNonVoidCallback",
            callbackType = "typedef:NonVoidCallback",
            routingUserdataParameter = "userdata",
        )

        assertDiagnostic(
            "typedef:NonVoidCallback: callback return type must be void, found int",
        ) {
            CallbackAnalyzer.validate(invalidIndex, config)
        }
    }

    @Test
    fun `rejects a userdata name with a non-opaque-pointer type`() {
        val invalidIndex = parseIndex(
            """
                typedef void (*BadUserdataCallback)(int userdata);
                void sampleSetBadUserdataCallback(BadUserdataCallback callback, void * userdata);
            """.trimIndent(),
        )
        val config = directConfig(
            function = "function:sampleSetBadUserdataCallback",
            callbackType = "typedef:BadUserdataCallback",
            routingUserdataParameter = "userdata",
        )

        assertDiagnostic(
            "typedef:BadUserdataCallback: parameter 'userdata' is named as userdata but has " +
                "non-opaque-pointer type int",
        ) {
            CallbackAnalyzer.validate(invalidIndex, config)
        }
    }

    @Test
    fun `rejects an ambiguous callback-info path in an owner`() {
        val config = validConfig().also {
            val binding = it.callbackInfoBindings.single()
            binding.owner?.function = "function:sampleOwnAmbiguousDescriptor"
            binding.owner?.parameterPath = "descriptor"
        }

        assertDiagnostic(
            "function:sampleOwnAmbiguousDescriptor: owner path 'descriptor' is ambiguous for " +
                "struct:SampleCallbackInfo (descriptor.first, descriptor.second)",
        ) {
            CallbackAnalyzer.validate(index, config)
        }
    }

    private fun validConfig(): CallbackBindingsConfig = CallbackBindingsConfig().also { config ->
        config.directFunctionBindings = listOf(
            DirectFunctionBinding().also {
                it.function = "function:sampleSetCallback"
                it.callbackParameter = "callback"
                it.callbackType = "typedef:SampleCallback"
                it.routingUserdataParameter = "userdata2"
            },
        )
        config.callbackInfoBindings = listOf(
            CallbackInfoBinding().also {
                it.struct = "struct:SampleCallbackInfo"
                it.owner = CallbackInfoOwner().also { owner ->
                    owner.function = "function:sampleOwnCallbackInfo"
                    owner.parameterPath = "callbackInfo"
                    owner.lifetime = CallbackInfoLifetime.CONSUMED_DURING_CALL
                }
                it.callbackField = "callback"
                it.callbackType = "typedef:SampleCallback"
                it.routingUserdataField = "userdata2"
                it.applicationUserdataFields = listOf("userdata1")
                it.mode = CallbackInfoMode().also { mode ->
                    mode.field = "mode"
                    mode.type = "typedef:SampleMode"
                    mode.allowedConstants = listOf(
                        "constant:SampleMode_Allow",
                        "constant:SampleMode_Spontaneous",
                    )
                }
            },
        )
    }

    private fun directConfig(
        function: String,
        callbackType: String,
        routingUserdataParameter: String,
    ): CallbackBindingsConfig = CallbackBindingsConfig().also { config ->
        config.directFunctionBindings = listOf(
            DirectFunctionBinding().also {
                it.function = function
                it.callbackParameter = "callback"
                it.callbackType = callbackType
                it.routingUserdataParameter = routingUserdataParameter
            },
        )
    }

    private fun assertDiagnostic(expected: String, block: () -> Unit) {
        val failure = assertFailsWith<CallbackBindingsException>(block = block)
        assertEquals(expected, failure.message)
    }

    private fun parseIndex(source: String): CanonicalDeclarationIndex {
        val header = tempDir.resolve("fixture-${source.hashCode()}.h").also { it.writeText(source) }
        return CanonicalDeclarationIndex(KextractTool.parse(listOf(header.toString())))
    }

    private fun programmaticIndex(vararg declarations: Declaration): CanonicalDeclarationIndex =
        CanonicalDeclarationIndex(Declaration.toplevel(Position.NO_POSITION, *declarations))

    private fun generateCommon(config: CallbackBindingsConfig, fixtureName: String): String {
        val header = tempDir.resolve("$fixtureName.h").also { it.writeText(FIXTURE_HEADER) }
        val output = tempDir.resolve("$fixtureName-output")
        assertEquals(
            KextractTool.SUCCESS,
            KextractTool(Logger()).runGeneration(
                listOf(header.toString()),
                Options(
                    targetPackage = "sample.bindings",
                    outputDir = output.toString(),
                    multiplatform = true,
                    callbackBindings = config,
                ),
            ),
        )
        return java.nio.file.Files.walk(output.resolve("commonMain")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .map { it.toFile().readText() }
                .toList()
                .joinToString("\n")
        }
    }

    private companion object {
        val FIXTURE_HEADER = """
            typedef unsigned int SampleMode;
            typedef unsigned int OtherMode;
            const SampleMode SampleMode_Allow = 1;
            const SampleMode SampleMode_Spontaneous = 2;
            const OtherMode OtherMode_Allow = 1;

            typedef void (*SampleCallback)(unsigned int value, void * userdata1, void * userdata2);
            typedef void (*NoUserdataCallback)(unsigned int value);

            typedef struct SampleCallbackInfo {
                SampleMode mode;
                SampleCallback callback;
                void * userdata1;
                void * userdata2;
            } SampleCallbackInfo;

            typedef struct SampleDescriptor {
                SampleCallbackInfo first;
                SampleCallbackInfo second;
            } SampleDescriptor;

            typedef struct SampleUniqueDescriptor {
                SampleCallbackInfo info;
            } SampleUniqueDescriptor;

            void sampleSetCallback(SampleCallback callback, void * userdata2);
            void sampleOwnCallbackInfo(SampleCallbackInfo callbackInfo);
            void sampleOwnAmbiguousDescriptor(SampleDescriptor const * descriptor);
            void sampleOwnNestedCallbackInfo(SampleUniqueDescriptor const * descriptor);
        """.trimIndent()
    }
}
