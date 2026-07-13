package org.graphiks.kextract.integration

import org.graphiks.kextract.testsupport.GeneratedSourceTestSupport
import org.graphiks.kextract.testsupport.GenerationRequest
import org.graphiks.kextract.testsupport.KotlinCompilerSupport
import org.graphiks.kextract.cli.DllEntry
import org.graphiks.kextract.cli.DllMap
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("generated-compile")
class GeneratedBindingsCompilationTest {
    @Test
    fun `C bindings compile`() {
        val files = GeneratedSourceTestSupport.generate(
            GenerationRequest(
                source = """
                    struct Point { int x; int y; };
                    union Value { int number; float decimal; };
                    enum Color { Red, Green, Blue };
                    struct Buffer { char bytes[16]; int *length; };
                    const int DefaultColor = Green;
                    int add(int left, int right);
                    void log_values(int count, ...);
                """.trimIndent(),
                variadicArgs = mapOf("log_values" to 2),
            )
        )

        val result = KotlinCompilerSupport.compile(files)

        assertEquals(0, result.exitCode, "${result.stdout}\n${result.stderr}")
    }

    @Test
    fun `C split output and init method compile`() {
        val files = GeneratedSourceTestSupport.generate(
            GenerationRequest(
                source = """
                    struct Point { int x; int y; };
                """.trimIndent(),
                splitOutput = true,
                useInitMethod = true,
            )
        )

        val result = KotlinCompilerSupport.compile(files)

        assertEquals(0, result.exitCode, "${result.stdout}\n${result.stderr}")
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `Objective-C bindings compile on macOS`() {
        val files = GeneratedSourceTestSupport.generate(
            GenerationRequest(
                language = org.graphiks.kextract.testsupport.HeaderLanguage.OBJECTIVE_C,
                source = """
                    @class NSString;

                    @interface NSObject
                    @end

                    @protocol KxGreeting
                    - (NSString *)requiredGreeting;
                    @end

                    @interface KxPerson : NSObject <KxGreeting>
                    @property (nonatomic, copy) NSString *name;
                    - (NSString *)greet:(NSString *)prefix;
                    @end

                    @interface KxPerson (Extras)
                    - (void)reset;
                    @end
                """.trimIndent(),
            )
        )

        val result = KotlinCompilerSupport.compile(files)

        assertEquals(0, result.exitCode, "${result.stdout}\n${result.stderr}")
    }

    @Test
    fun `Win32 bindings compile without loading a DLL`() {
        val files = GeneratedSourceTestSupport.generate(
            GenerationRequest(
                source = """
                    struct Window { int width; int height; };
                    int GetTickCount(void);
                    const int WindowStyle = 1;
                """.trimIndent(),
                win32Mode = true,
                dllMap = DllMap(
                    mapOf(
                        "kernel32.dll" to DllEntry(functions = listOf("GetTickCount")),
                        "user32.dll" to DllEntry(constants = listOf("WindowStyle")),
                    )
                ),
            )
        )

        val result = KotlinCompilerSupport.compile(files)

        assertEquals(0, result.exitCode, "${result.stdout}\n${result.stderr}")
    }
}
