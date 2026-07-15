package org.graphiks.kextract.clang

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals

class LibClangProcessEnvironmentTest {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `test worker disables LLVM crash recovery before startup`() {
        assertEquals("1", System.getenv("LIBCLANG_DISABLE_CRASH_RECOVERY"))
    }
}
