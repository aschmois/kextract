package org.graphiks.kextract.pipeline

import com.github.ajalt.clikt.core.main
import org.graphiks.kextract.Declaration
import org.graphiks.kextract.cli.DllMap
import org.graphiks.kextract.kotlin.KotlinGenerator
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * kextract tool — orchestrates parsing, filtering and code generation.
 * CLI parsing is handled by [KextractCommand] (Clikt).
 */
class KextractTool(private val logger: Logger) {

    companion object {
        val DEBUG: Boolean = System.getProperty("kextract.debug") == "true"
        private val isMacOSX: Boolean = System.getProperty("os.name") == "Mac OS X"

        private val SPECIAL_HEADERS = setOf(
            "stdarg.h", "stddef.h", "stdint.h", "stdbool.h", "stdalign.h",
            "stdnoreturn.h", "stdckdint.h", "stdatomic.h"
        )

        // Exit codes
        const val SUCCESS      = 0
        const val FAILURE      = 1
        const val OPTION_ERROR = 2
        const val INPUT_ERROR  = 3
        const val CLANG_ERROR  = 4
        const val FATAL_ERROR  = 5
        const val OUTPUT_ERROR = 6

        /**
         * Main entry point.
         * Preprocesses GNU-style concatenated options (-DFOO, -I/path) then delegates to Clikt.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val preprocessed = CommandLine.parse(expandGnuArgs(args.toList()))
            try {
                KextractCommand(Logger.DEFAULT).main(preprocessed)
            } catch (e: Throwable) {
                System.err.println("kextract.fatal: ${e.message}")
                e.printStackTrace(System.err)
                if (e.cause != null) {
                    System.err.println("kextract.fatal.cause: ${e.cause!!.message}")
                    e.cause!!.printStackTrace(System.err)
                }
                System.exit(1)
            }
        }

        /**
         * Parse headers directly — used by tests and programmatic callers.
         */
        @JvmStatic
        fun parse(headers: List<String>, vararg parserOptions: String): Declaration.Scoped {
            val source = headers.joinToString("\n") { header ->
                if (isSpecialHeaderName(header)) "#include $header" else "#include \"$header\""
            }
            return Parser(Logger.DEFAULT).parse("kextract\$tmp.h", source, parserOptions.toList())
        }

        /**
         * Expands GNU-style concatenated short options:
         *   -DFOO=1  →  ["-D", "FOO=1"]
         *   -I/path  →  ["-I", "/path"]
         * Other args pass through unchanged.
         */
        @JvmStatic
        fun expandGnuArgs(args: List<String>): List<String> = buildList {
            for (arg in args) {
                when {
                    arg.startsWith("-D") && arg.length > 2 -> { add("-D"); add(arg.substring(2)) }
                    arg.startsWith("-I") && arg.length > 2 -> { add("-I"); add(arg.substring(2)) }
                    else -> add(arg)
                }
            }
        }

        private fun isSpecialHeaderName(header: String): Boolean =
            (header.startsWith("<") && header.endsWith(">")) || header in SPECIAL_HEADERS
    }

    // ── Generation pipeline ───────────────────────────────────────────────────

    /**
     * Parse headers and generate Kotlin bindings according to [options].
     * Returns one of the [SUCCESS]/[FAILURE]/… exit codes.
     */
    fun runGeneration(headers: List<String>, options: Options): Int {
        val outputDir = Path.of(options.outputDir)
        try {
            outputDir.createDirectories()
        } catch (e: Exception) {
            logger.err("kextract.output.dir.create.failed", outputDir.toString(), e.message ?: "")
            return OUTPUT_ERROR
        }

        val effectiveClangArgs = withBuiltinResourceDir(options.clangArgs)
        val decl: Declaration.Scoped = try {
            Parser(logger).parse("kextract\$tmp.h", generateTmpSource(headers), effectiveClangArgs)
        } catch (e: Throwable) {
            logger.err("kextract.parse.failed", e.message ?: "")
            if (e.cause != null) logger.err("kextract.parse.cause", e.cause!!.message ?: "")
            if (DEBUG) e.printStackTrace()
            return FAILURE
        }

        if (DEBUG) {
            System.err.println("Parsed ${decl.members().size} top-level declarations:")
            decl.members().forEach { m -> System.err.println("  ${m.javaClass.simpleName} name=${m.name()}") }
        }

        if (hasObjCDeclarations(decl) && !isMacOSX) {
            logger.warn("kextract.objc.non.macos.warning")
        }

        options.includeHelper.setFrameworkPaths(resolveFrameworkPaths(options.includeFrameworks))

        val results: List<KotlinSourceFile> = try {
            generate(decl, headers[0], options)
        } catch (e: Exception) {
            logger.err("kextract.generation.failed", e.message ?: "")
            if (DEBUG) e.printStackTrace()
            return FAILURE
        }

        if (DEBUG) {
            System.err.println("${results.size} result files:")
            results.forEach { r -> System.err.println("  ${r.getPath()}") }
        }

        return writeKotlin(results, outputDir)
    }

    private fun resolveFrameworkPaths(names: List<String>): List<Path> {
        if (names.isEmpty()) return emptyList()
        return try {
            val proc = ProcessBuilder("xcrun", "--sdk", "macosx", "--show-sdk-path")
                .redirectErrorStream(true)
                .start()
            val sdk = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() != 0) {
                logger.warn("kextract.framework.sdk.not.found")
                return emptyList()
            }
            names.map { name ->
                Path.of(sdk, "System/Library/Frameworks", "${name}.framework", "Headers")
            }
        } catch (e: Exception) {
            logger.warn("kextract.framework.sdk.error", e.message ?: "")
            emptyList()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun generate(
        decl: Declaration.Scoped,
        headerName: String,
        options: Options
    ): List<KotlinSourceFile> {
        var d = decl
        d = IncludeFilter(options.includeHelper).scan(d)
        d = DuplicateFilter().scan(d)
        d = UnsupportedFilter(logger).scan(d)
        d = MissingDepChecker(logger).scan(d)
        if (logger.hasErrors()) return emptyList()

        val transformed = NameMangler(headerName).scan(d)
        return KotlinGenerator().generate(
            transformed, headerName, options.targetPackage,
            options.libraries, options.useSystemLoadLibrary,
            options.splitOutput, options.variadicArgs,
            options.win32Mode, options.dllMap,
            options.useInitMethod
        )
    }

    private fun writeKotlin(results: List<KotlinSourceFile>, outputDir: Path): Int {
        return try {
            for (result in results) {
                val outputPath = outputDir.resolve(result.getPath())
                outputPath.parent.createDirectories()
                outputPath.writeText(result.contents)
            }
            SUCCESS
        } catch (e: Exception) {
            System.err.println("Error writing Kotlin files: ${e.message}")
            OUTPUT_ERROR
        }
    }

    private fun generateTmpSource(headers: List<String>): String =
        headers.joinToString("\n") { header ->
            if (isSpecialHeaderName(header)) "#include $header" else "#include \"$header\""
        }

    private fun hasObjCDeclarations(decl: Declaration.Scoped): Boolean =
        decl.members().any { it is Declaration.ObjCClass || it is Declaration.ObjCProtocol || it is Declaration.ObjCCategory }

    /**
     * Prepends `-I <kextract-conf>` to [args] so clang can find its built-in headers
     * (stdarg.h, stddef.h, etc.) from the kextract distribution, even when combined
     * with `-isysroot` (where `-resource-dir` would be overridden by the SDK).
     *
     * Detection: locates `org.graphiks.kextract.jar` on the classpath → navigates
     * to its parent (lib/) → sibling `conf/kextract/` has the builtin headers.
     *
     * Skipped if the conf dir cannot be found (e.g. running from source without building).
     */
    private fun withBuiltinResourceDir(args: List<String>): List<String> {
        val confDir = detectBuiltinDir() ?: return args
        return listOf("-I", confDir) + args
    }

    private fun detectBuiltinDir(): String? {
        val cp = System.getProperty("java.class.path") ?: return null
        val jar = cp.split(File.pathSeparator)
            .map(::File)
            .firstOrNull { it.name.startsWith("org.graphiks.kextract") && it.name.endsWith(".jar") }
            ?: return null
        val confDir = jar.parentFile?.parentFile?.resolve("conf/kextract")
        return if (confDir?.isDirectory == true) confDir.absolutePath else null
    }
}
