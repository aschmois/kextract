package org.graphiks.kextract

import io.kotest.core.config.AbstractProjectConfig

/**
 * Kotest project config.
 *
 * On Linux the JVM exits with a spurious SIGSEGV in libc syscall+0x1d after all
 * tests have completed but before the process actually terminates — apparently a
 * collision between libclang's atexit handlers and the JVM shutdown sequence.
 * Tests are all green by that point, but Gradle still reports the task as failed
 * (exit code 134).
 *
 * After the spec engine finishes, we bypass the offending shutdown path by
 * calling Runtime.halt(0). All test results have already been written to disk by
 * the JUnit/Kotest engine at this point.
 */
object TestProjectConfig : AbstractProjectConfig() {
    override suspend fun afterProject() {
        val osName = System.getProperty("os.name", "").lowercase()
        // Only the Linux JVM exhibits the libclang/JVM shutdown SIGSEGV.
        if ("linux" in osName) {
            // Flush stdout/stderr before halting so test output isn't truncated.
            System.out.flush()
            System.err.flush()
            Runtime.getRuntime().halt(0)
        }
    }
}
