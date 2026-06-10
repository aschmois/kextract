// src/main/kotlin/org/openjdk/kextract/kotlin/builders/KotlinToplevelBuilder.kt
package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.kotlin.utils.KotlinNameMangler
import org.graphiks.kextract.pipeline.Options

/**
 * Top-level builder for Kotlin files.
 * Coordinates generation of all declarations (structs, functions, ObjC classes, etc.).
 */
class KotlinToplevelBuilder(
    private val targetPackage: String,
    val className: String,
    private val headerName: String,
    private val libraries: List<Options.Library> = emptyList(),
    private val useSystemLoadLibrary: Boolean = false,
    private val splitOutput: Boolean = false
) : Declaration.Visitor<Unit> {
    private val builder = SourceBuilder()
    private val files = mutableListOf<KotlinSourceFile>()
    private val headerBuilder = KotlinHeaderBuilder(builder, this)
    private val structBuilder = KotlinStructBuilder(builder, this)
    private val typedefBuilder = KotlinTypedefBuilder(builder, this)
    // objcClassBuilder is initialised lazily after generatedObjCClassNames is populated
    private var objcClassBuilder = KotlinObjCClassBuilder(builder, this)
    private val objcProtocolBuilder = KotlinObjCProtocolBuilder(builder, this)
    private val objcCategoryBuilder = KotlinObjCCategoryBuilder(builder, this)
    private val enumBuilder = KotlinEnumBuilder(builder, this)

    /** True if any ObjC declaration was encountered — triggers ObjCRuntime.kt emission. */
    var needsObjCRuntime: Boolean = false
        private set

    /** True when a LOOKUP val was generated (libraries were provided). */
    val hasLookup: Boolean get() = libraries.isNotEmpty()

    init {
        // Package declaration
        if (targetPackage.isNotEmpty()) {
            builder.appendLine("package ${targetPackage}")
            builder.appendLine()
        }

        // Standard imports
        builder.appendLine("import java.lang.invoke.*")
        builder.appendLine("import java.lang.foreign.*")
        builder.appendLine("import java.lang.foreign.MemoryLayout.PathElement.*")
        builder.appendLine()

        // Helper constants for layouts
        builder.appendLine("private object kextract_runtime {")
        builder.indent()
        builder.appendLine("val C_BOOL: ValueLayout = ValueLayout.JAVA_BOOLEAN")
        builder.appendLine("val C_CHAR: ValueLayout = ValueLayout.JAVA_BYTE")
        builder.appendLine("val C_SHORT: ValueLayout = ValueLayout.JAVA_SHORT")
        builder.appendLine("val C_INT: ValueLayout = ValueLayout.JAVA_INT")
        builder.appendLine("val C_LONG: ValueLayout = ValueLayout.JAVA_LONG")
        builder.appendLine("val C_LONG_LONG: ValueLayout = ValueLayout.JAVA_LONG")
        builder.appendLine("val C_FLOAT: ValueLayout = ValueLayout.JAVA_FLOAT")
        builder.appendLine("val C_DOUBLE: ValueLayout = ValueLayout.JAVA_DOUBLE")
        builder.appendLine("val C_POINTER: ValueLayout = ValueLayout.ADDRESS")
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()

        // Symbol lookup — loads native libraries and exposes a single LOOKUP
        if (libraries.isNotEmpty()) {
            builder.appendLine("private val LOOKUP: SymbolLookup = run {")
            builder.indent()
            if (useSystemLoadLibrary) {
                for (lib in libraries) {
                    builder.appendLine("System.loadLibrary(\"${lib.libSpec}\")")
                }
                builder.appendLine("SymbolLookup.loaderLookup()")
            } else {
                builder.appendLine("var lu: SymbolLookup = SymbolLookup.loaderLookup()")
                for (lib in libraries) {
                    val lookup = when (lib.specKind) {
                        Options.Library.SpecKind.PATH ->
                            "SymbolLookup.libraryLookup(\"${Options.Library.toQuotedName(lib)}\", Arena.global())"
                        Options.Library.SpecKind.NAME ->
                            "SymbolLookup.libraryLookup(\"${lib.libSpec}\", Arena.global())"
                    }
                    builder.appendLine("lu = $lookup.or(lu)")
                }
                builder.appendLine("lu")
            }
            builder.unindent()
            builder.appendLine("}")
            builder.appendLine()
        }
    }

    override fun visitScoped(decl: Declaration.Scoped) {
        if (Skip.isPresent(decl)) return
        when (decl.kind()) {
            Declaration.Scoped.Kind.STRUCT -> structBuilder.visitStruct(decl)
            Declaration.Scoped.Kind.UNION  -> structBuilder.visitUnion(decl)
            Declaration.Scoped.Kind.ENUM   -> {
                // Only generate for named enums with constants.
                // Anonymous enums (name == "") appear only as typedef targets and are never
                // emitted here since the typedef path handles them via typealias.
                // For ObjC fixed-underlying-type enums (typedef enum : long { … } Foo),
                // clang creates a named ENUM scoped with the typedef name, and the redundant
                // typedef is filtered — so this is the only place we emit the enum class.
                if (decl.name().isNotEmpty()) {
                    enumBuilder.visitEnum(decl)
                }
            }
            else -> {
                // TOPLEVEL: pre-scan before generating code.
                if (decl.kind() == Declaration.Scoped.Kind.TOPLEVEL) {
                    // Collect the names of all enum constants from named ENUMs inside TOPLEVEL.
                    // Mark those named-ENUM scopeds and their constants as Skip so they are not
                    // re-visited as standalone items.
                    // Also mark any top-level macro Declaration.Constant whose name matches an
                    // enum constant (clang synthesises these for each ObjC enum member).
                    val enumConstantNames = mutableSetOf<String>()
                    decl.members()
                        .filterIsInstance<Declaration.Scoped>()
                        .filter { it.kind() == Declaration.Scoped.Kind.ENUM && it.name().isNotEmpty() && !Skip.isPresent(it) }
                        .forEach { enumScoped ->
                            enumScoped.members()
                                .filterIsInstance<Declaration.Constant>()
                                .forEach { constant ->
                                    enumConstantNames.add(constant.name())
                                    // The constants themselves will be emitted inside the enum
                                    // class; mark them so they are not re-emitted as globals.
                                    Skip.with(constant)
                                }
                        }
                    // Suppress macro-synthesised constants that shadow enum member names
                    if (enumConstantNames.isNotEmpty()) {
                        decl.members()
                            .filterIsInstance<Declaration.Constant>()
                            .filter { it.name() in enumConstantNames && !Skip.isPresent(it) }
                            .forEach { constant -> Skip.with(constant) }
                    }
                    // Collect generated ObjCClass names so the class builder can emit superclass
                    // clauses only for classes that will actually be generated (GRA-79).
                    val generatedObjCClassNames = decl.members()
                        .filterIsInstance<Declaration.ObjCClass>()
                        .filter { !Skip.isPresent(it) }
                        .map { it.name() }
                        .toSet()
                    objcClassBuilder = KotlinObjCClassBuilder(builder, this, generatedObjCClassNames)
                }
                // Process all members
                for (d in decl.members()) {
                    d.accept(this)
                }
            }
        }

        // Only add file for TOPLEVEL scoped (not for nested structs/unions)
        if (decl.kind() == Declaration.Scoped.Kind.TOPLEVEL) {
            files.add(KotlinSourceFile(targetPackage, className, builder.toString()))
        }
    }

    override fun visitFunction(decl: Declaration.Function) {
        if (Skip.isPresent(decl)) return
        headerBuilder.visitFunction(decl)
    }

    override fun visitVariable(decl: Declaration.Variable) {
        if (Skip.isPresent(decl)) return
        headerBuilder.visitVariable(decl)
    }

    override fun visitTypedef(decl: Declaration.Typedef) {
        if (Skip.isPresent(decl)) return
        typedefBuilder.visitTypedef(decl)
    }

    override fun visitConstant(decl: Declaration.Constant) {
        if (Skip.isPresent(decl)) return
        headerBuilder.visitConstant(decl)
    }

    override fun visitObjCClass(decl: Declaration.ObjCClass) {
        if (Skip.isPresent(decl)) return
        needsObjCRuntime = true
        objcClassBuilder.visitClass(decl)
    }

    override fun visitObjCProtocol(decl: Declaration.ObjCProtocol) {
        if (Skip.isPresent(decl)) return
        needsObjCRuntime = true
        objcProtocolBuilder.visitProtocol(decl)
    }

    override fun visitObjCCategory(decl: Declaration.ObjCCategory) {
        if (Skip.isPresent(decl)) return
        needsObjCRuntime = true
        objcCategoryBuilder.visitCategory(decl)
    }

    fun getFiles(): List<KotlinSourceFile> = files

    fun javaName(name: String): String = KotlinNameMangler.mangle(name)

    fun lookupName(decl: Declaration): String = decl.name()
}
