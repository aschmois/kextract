package org.graphiks.kextract.pipeline

import org.graphiks.kextract.cli.DllMap

/**
 * Immutable configuration snapshot passed through the generation pipeline.
 */
data class Options(
    val clangArgs: List<String> = emptyList(),
    val libraries: List<Library> = emptyList(),
    val useSystemLoadLibrary: Boolean = false,
    val targetPackage: String = "",
    val outputDir: String = ".",
    val sharedClassName: String? = null,
    val splitOutput: Boolean = false,
    val variadicArgs: Map<String, Int> = emptyMap(),
    val includeFrameworks: List<String> = emptyList(),
    val includeHelper: IncludeHelper = IncludeHelper(),
    val win32Mode: Boolean = false,
    val dllMap: DllMap? = null,
) {
    /** A shared library descriptor. */
    data class Library(val libSpec: String, val specKind: SpecKind) {
        enum class SpecKind { NAME, PATH }

        companion object {
            fun parse(optionString: String): Library {
                val specKind = if (optionString.startsWith(":")) SpecKind.PATH else SpecKind.NAME
                return if (specKind == SpecKind.PATH) {
                    if (optionString.length == 1) throw IllegalArgumentException("Empty library specifier")
                    Library(optionString.substring(1), specKind)
                } else {
                    Library(optionString, specKind)
                }
            }

            fun toQuotedName(lib: Library): String = lib.libSpec.replace("\\", "\\\\")
        }
    }
}
