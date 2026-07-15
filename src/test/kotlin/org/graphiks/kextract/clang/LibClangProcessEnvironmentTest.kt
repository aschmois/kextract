package org.graphiks.kextract.clang

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import kotlin.test.assertEquals

class LibClangProcessEnvironmentTest {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `test worker disables LLVM crash recovery before startup`() {
        assertEquals("1", System.getenv("LIBCLANG_DISABLE_CRASH_RECOVERY"))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `test worker resolves the signal chaining library without an absolute preload path`() {
        val expectedLibraryDirectory = File(System.getProperty("java.home"), "lib")
            .absoluteFile
            .normalize()
        val preloadEntries = System.getenv("LD_PRELOAD")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
        val libraryPathEntries = System.getenv("LD_LIBRARY_PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map { File(it).absoluteFile.normalize() }

        assertEquals(
            "libjsig.so",
            preloadEntries.firstOrNull(),
            "Expected LD_PRELOAD to resolve libjsig by basename before inherited entries",
        )
        assertEquals(
            expectedLibraryDirectory,
            libraryPathEntries.firstOrNull(),
            "Expected LD_LIBRARY_PATH to search the test JDK before inherited entries",
        )
    }
}
