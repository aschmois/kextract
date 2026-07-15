package org.graphiks.kextract.clang

import kotlin.test.Test
import kotlin.test.assertEquals

class CursorAnonymousRecordTest {

    @Test
    fun `libclang distinguishes anonymous members unnamed record types and named records`() {
        val header =
            """
            struct Container {
                union { int embedded; };
                union { int named; } namedField;
            };
            struct Named { int value; };
            """.trimIndent()

        LibClang.createIndex(false).use { index ->
            index.parse("anonymous-record-signals.h", header, false).use { translationUnit ->
                val records = mutableMapOf<Int, RecordSignals>()

                fun collect(cursor: Cursor) {
                    if (cursor.kindOrNull() in setOf(CursorKind.StructDecl, CursorKind.UnionDecl)) {
                        val line = cursor.getSourceLocation()!!.getSpellingLocation().line
                        records[line] = RecordSignals(
                            anonymousRecordDecl = cursor.isAnonymousStruct(),
                            anonymous = cursor.isAnonymous(),
                        )
                    }
                    cursor.forEach(::collect)
                }

                collect(translationUnit.getCursor())

                assertEquals(
                    mapOf(
                        1 to RecordSignals(anonymousRecordDecl = false, anonymous = false),
                        2 to RecordSignals(anonymousRecordDecl = true, anonymous = true),
                        3 to RecordSignals(anonymousRecordDecl = false, anonymous = true),
                        5 to RecordSignals(anonymousRecordDecl = false, anonymous = false),
                    ),
                    records,
                )
            }
        }
    }

    private data class RecordSignals(
        val anonymousRecordDecl: Boolean,
        val anonymous: Boolean,
    )
}
