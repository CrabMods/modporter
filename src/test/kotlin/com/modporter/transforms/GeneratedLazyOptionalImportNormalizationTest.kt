package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateGeneratedLazyOptionalImportSource
import com.modporter.core.transforms.util.JavaSourceText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedLazyOptionalImportNormalizationTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `existing generated import removes legacy code tokens while preserving every comment and literal byte`() {
        val legacy = "net.neoforged.neoforge.common.util.LazyOptional"
        val generated = "com.modporter.generated.create.compat.LazyOptional"
        val fakeBlock = """
            /*
            import $legacy;
            import $generated;
            */
        """.trimIndent()
        val fakeTextBlock = "\"\"\"\nimport $legacy;\nimport $generated;\n\"\"\""
        val legacyLine = "/* keep-leading-legacy */ import /* keep-after-legacy-keyword */ $legacy /* keep-before-legacy-semicolon */ ; // keep-neo-comment"
        val generatedLine = "import /* keep-after-generated-keyword */ $generated /* keep-before-generated-semicolon */ ; // keep-generated-comment"
        val duplicateLine = "/* keep-leading-duplicate */ import /* keep-after-duplicate-keyword */ $generated; // keep-duplicate-comment"
        val source = javaSource(
            listOf(fakeBlock, legacyLine, generatedLine, duplicateLine).joinToString("\n"),
            "private String marker = $fakeTextBlock;\nprivate LazyOptional<String> value;"
        )
        var supplierCalls = 0

        val migrated = pass.migrateGeneratedLazyOptionalImportSource(source) {
            supplierCalls++
            error("existing generated imports must not resolve a new package")
        }

        assertEquals(0, supplierCalls)
        assertEquals(0, importCount(migrated, legacy), migrated)
        assertEquals(1, importCount(migrated, generated), migrated)
        assertTrue(migrated.contains(fakeBlock), migrated)
        assertTrue(migrated.contains(fakeTextBlock), migrated)
        listOf(
            "/* keep-leading-legacy */",
            "/* keep-after-legacy-keyword */",
            "/* keep-before-legacy-semicolon */",
            "// keep-neo-comment",
            generatedLine,
            "/* keep-leading-duplicate */",
            "/* keep-after-duplicate-keyword */",
            "// keep-duplicate-comment"
        ).forEach { preserved -> assertTrue(migrated.contains(preserved), preserved) }
        assertEquals(
            migrated,
            pass.migrateGeneratedLazyOptionalImportSource(migrated) {
                error("idempotent re-entry must not resolve a new package")
            }
        )
    }

    @Test
    fun `Forge and global compatibility aliases are replaced by one generated import`() {
        val generated = "com.modporter.generated.example.compat.LazyOptional"
        listOf(
            "net.minecraftforge.common.util.LazyOptional",
            "com.modporter.compat.LazyOptional"
        ).forEach { legacy ->
            val source = javaSource("import $legacy;", "private LazyOptional<String> value;")
            var supplierCalls = 0

            val migrated = pass.migrateGeneratedLazyOptionalImportSource(source) {
                supplierCalls++
                "com.modporter.generated.example.compat"
            }

            assertEquals(1, supplierCalls, legacy)
            assertEquals(0, importCount(migrated, legacy), migrated)
            assertEquals(1, importCount(migrated, generated), migrated)
        }
    }

    @Test
    fun `third party import and trailing multiline comment remain intact when generated import is added`() {
        val thirdParty = "third.party.common.LazyOptional"
        val generated = "com.modporter.generated.example.compat.LazyOptional"
        val thirdPartyImport = "import $thirdParty; /* preserve-third-party-block\ncontinuation of third-party block */"
        val source = javaSource(thirdPartyImport, "private LazyOptional<String> value;")

        val migrated = pass.migrateGeneratedLazyOptionalImportSource(source) {
            "com.modporter.generated.example.compat"
        }

        assertEquals(1, importCount(migrated, thirdParty), migrated)
        assertEquals(1, importCount(migrated, generated), migrated)
        assertTrue(migrated.contains(thirdPartyImport), migrated)
        assertTrue(
            migrated.indexOf("import $generated;") > migrated.indexOf("continuation of third-party block */"),
            migrated
        )
        assertEquals(
            migrated,
            pass.migrateGeneratedLazyOptionalImportSource(migrated) {
                error("added generated import must be stable on re-entry")
            }
        )
    }

    @Test
    fun `masked imports cannot redirect replacement and real inter-token comments stay byte exact`() {
        val legacy = "net.minecraftforge.common.util.LazyOptional"
        val generated = "com.modporter.generated.example.compat.LazyOptional"
        val fakeBlock = """
            /*
            import $legacy;
            import $generated;
            */
        """.trimIndent()
        val fakeTextBlock = "\"\"\"\nimport $legacy;\nimport $generated;\n\"\"\""
        val legacyLine = "/* preserve-leading */ import /* preserve-after-keyword */ $legacy /* preserve-before-semicolon */ ; // preserve-legacy-comment"
        val expectedGeneratedLine = legacyLine.replace(legacy, generated)
        val source = javaSource(
            listOf(fakeBlock, legacyLine).joinToString("\n"),
            "private String marker = $fakeTextBlock;\nprivate LazyOptional<String> value;"
        )
        var supplierCalls = 0

        val migrated = pass.migrateGeneratedLazyOptionalImportSource(source) {
            supplierCalls++
            "com.modporter.generated.example.compat"
        }

        assertEquals(1, supplierCalls)
        assertEquals(0, importCount(migrated, legacy), migrated)
        assertEquals(1, importCount(migrated, generated), migrated)
        assertTrue(migrated.contains(fakeBlock), migrated)
        assertTrue(migrated.contains(fakeTextBlock), migrated)
        assertTrue(migrated.contains(expectedGeneratedLine), migrated)
        assertEquals(
            migrated,
            pass.migrateGeneratedLazyOptionalImportSource(migrated) {
                error("normalized source must be idempotent")
            }
        )
    }

    @Test
    fun `distinct generated compatibility imports remain visible while legacy import is removed`() {
        val firstGenerated = "com.modporter.generated.create.compat.LazyOptional"
        val secondGenerated = "com.modporter.generated.other.compat.LazyOptional"
        val legacy = "com.modporter.compat.LazyOptional"
        val source = javaSource(
            listOf(
                "import $legacy; // preserve-legacy-tail",
                "import $firstGenerated; // preserve-first-tail",
                "import $secondGenerated; // preserve-second-tail"
            ).joinToString("\n"),
            "private LazyOptional<String> value;"
        )

        val migrated = pass.migrateGeneratedLazyOptionalImportSource(source) {
            error("visible generated conflict must not resolve another package")
        }

        assertEquals(0, importCount(migrated, legacy), migrated)
        assertEquals(1, importCount(migrated, firstGenerated), migrated)
        assertEquals(1, importCount(migrated, secondGenerated), migrated)
        listOf("// preserve-legacy-tail", "// preserve-first-tail", "// preserve-second-tail").forEach { comment ->
            assertTrue(migrated.contains(comment), migrated)
        }
    }

    @Test
    fun `comment and string only LazyOptional text does not call package supplier`() {
        val source = javaSource(
            "import net.neoforged.neoforge.common.util.LazyOptional; // untouched-no-use-comment",
            "// LazyOptional<String> is documentation only.\nprivate String marker = \"LazyOptional<Integer>\";"
        )
        var supplierCalls = 0

        val migrated = pass.migrateGeneratedLazyOptionalImportSource(source) {
            supplierCalls++
            "com.modporter.generated.example.compat"
        }

        assertEquals(0, supplierCalls)
        assertEquals(source, migrated)
    }

    private fun javaSource(imports: String, body: String): String = buildString {
        append("package example;\n\n")
        append(imports)
        append("\n\npublic class Example {\n")
        append(body)
        append("\n}\n")
    }

    private fun importCount(source: String, qualifiedName: String): Int {
        val qualifiedNamePattern = qualifiedName
            .split('.')
            .joinToString("""[ \t\r\n\f]*\.[ \t\r\n\f]*""") { Regex.escape(it) }
        return Regex(
            """(?m)^[ \t]*import\b[ \t\r\n\f]+$qualifiedNamePattern[ \t\r\n\f]*;"""
        ).findAll(JavaSourceText.maskCommentsAndLiterals(source)).count()
    }
}
