// src/main/kotlin/org/openjdk/kextract/kotlin/KotlinGenerator.kt
package org.graphiks.kextract.kotlin

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.callbacks.ValidatedCallbackBindings
import org.graphiks.kextract.cli.DllMap
import org.graphiks.kextract.kotlin.builders.KotlinKmpAndroidBuilder
import org.graphiks.kextract.kotlin.builders.KotlinKmpCommonBuilder
import org.graphiks.kextract.kotlin.builders.KotlinKmpJvmBuilder
import org.graphiks.kextract.kotlin.builders.KotlinKmpNativeBuilder
import org.graphiks.kextract.kotlin.builders.KotlinToplevelBuilder
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.kotlin.objc.ObjCRuntimeTemplate
import org.graphiks.kextract.kotlin.objc.ObjCSubclassingTemplate
import org.graphiks.kextract.pipeline.Options

/**
 * Main entry point for Kotlin code generation.
 * This class is callable from Java (via KextractTool).
 */
class KotlinGenerator {
    /**
     * Generates Kotlin source files from a C/ObjC AST.
     *
     * When Objective-C declarations are present an additional `ObjCRuntime.kt`
     * helper file is automatically included in the result so callers can use
     * `ObjCRuntime.msgSend` / `ObjCRuntime.sel` / `ObjCRuntime.getClass`.
     *
     * @param scoped The root declaration (parsed from C/ObjC headers).
     * @param headerName The name of the header file (e.g., "mylib.h").
     * @param targetPackage The target package (e.g., "org.mylib").
     * @param libraries Libraries to load at runtime (used to generate lookup code).
     * @param useSystemLoadLibrary When true use System.loadLibrary instead of libraryLookup.
     * @return List of generated Kotlin source files.
     */
    fun generate(
        scoped: Declaration.Scoped,
        headerName: String,
        targetPackage: String,
        libraries: List<Options.Library> = emptyList(),
        useSystemLoadLibrary: Boolean = false,
        splitOutput: Boolean = false,
        variadicArgs: Map<String, Int> = emptyMap(),
        win32Mode: Boolean = false,
        dllMap: DllMap? = null,
        useInitMethod: Boolean = false,
        multiplatform: Boolean = false,
        callbackBindings: ValidatedCallbackBindings = ValidatedCallbackBindings.EMPTY,
    ): List<KotlinSourceFile> {
        val className = sanitizeClassName(headerName)
        if (multiplatform) return generateKmp(scoped, targetPackage, className)

        val toplevel = KotlinToplevelBuilder(
            targetPackage, className, headerName, libraries, useSystemLoadLibrary, splitOutput, variadicArgs,
            win32Mode, dllMap, useInitMethod,
        )
        scoped.accept(toplevel)
        return toplevel.getFiles().toMutableList().apply {
            if (toplevel.needsObjCRuntime) {
                add(ObjCRuntimeTemplate.generate(targetPackage))
                add(ObjCSubclassingTemplate.generate(targetPackage))
            }
        }
    }

    private fun generateKmp(
        scoped: Declaration.Scoped,
        targetPackage: String,
        className: String,
    ): List<KotlinSourceFile> = buildList {
        KotlinKmpCommonBuilder(targetPackage, className).also { scoped.accept(it); addAll(it.getFiles()) }
        KotlinKmpJvmBuilder(targetPackage, className).also { scoped.accept(it); addAll(it.getFiles()) }
        KotlinKmpAndroidBuilder(targetPackage, className).also { scoped.accept(it); addAll(it.getFiles()) }
        KotlinKmpNativeBuilder(targetPackage, className).also { scoped.accept(it); addAll(it.getFiles()) }
    }

    private fun sanitizeClassName(name: String): String =
        name.substringAfterLast('/')
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .replace(Regex("^\\d+"), "_")
}
