package org.graphiks.kextract.clang

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibClangProcessEnvironmentTest {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `test worker disables LLVM crash recovery before startup`() {
        assertEquals("1", System.getenv("LIBCLANG_DISABLE_CRASH_RECOVERY"))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `test worker preloads the JDK signal chaining library before startup`() {
        val expectedLibjsig = File(System.getProperty("java.home"), "lib/libjsig.so")
            .absoluteFile
            .normalize()
        val preloadEntries = System.getenv("LD_PRELOAD")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map { File(it).absoluteFile.normalize() }

        assertTrue(
            expectedLibjsig in preloadEntries,
            "Expected LD_PRELOAD to contain $expectedLibjsig, but was $preloadEntries",
        )
    }
}
