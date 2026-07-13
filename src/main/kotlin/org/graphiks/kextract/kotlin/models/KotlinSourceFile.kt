// src/main/kotlin/org/openjdk/kextract/kotlin/models/KotlinSourceFile.kt
package org.graphiks.kextract.kotlin.models

import java.nio.file.Path

/**
 * Represents a generated Kotlin source file.
 * @param packageName The package (e.g., "org.mylib").
 * @param className The class name (e.g., "mylib_h").
 * @param contents The Kotlin code.
 * @param subDirectory An optional directory below the package path.
 * @param sourceRoot An optional directory preceding the package path.
 */
data class KotlinSourceFile(
    val packageName: String,
    val className: String,
    val contents: String,
    val subDirectory: String = "",
    val sourceRoot: String = "",
) {
    /**
     * Returns the file path (e.g., "commonMain/kotlin/org/mylib/generated/mylib_h.kt").
     */
    fun getPath(): Path {
        var path = Path.of(packageName.replace('.', '/'))
        if (subDirectory.isNotEmpty()) path = path.resolve(subDirectory)
        path = path.resolve("$className.kt")
        return if (sourceRoot.isEmpty()) path else Path.of(sourceRoot).resolve(path)
    }

    /**
     * Returns the full qualified name (e.g., "org.mylib.mylib_h").
     */
    fun getQualifiedName(): String = "$packageName.$className"

}
