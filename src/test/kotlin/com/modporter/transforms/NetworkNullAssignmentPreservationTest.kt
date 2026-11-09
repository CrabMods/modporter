package com.modporter.transforms

import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkNullAssignmentPreservationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `nullable business network state is cleared before reading an absent value`() {
        val sourceRoot = tempDir.resolve("src/main/java")
        val sourceFile = sourceRoot.resolve("com/example/NetworkResetFixture.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example;

            public final class NetworkResetFixture {
                private Long network;

                private void clearKineticInformation() {
                    network = null;
                }

                private void read(boolean hasNetwork, long value) {
                    clearKineticInformation();
                    if (hasNetwork)
                        network = value;
                }

                public static void main(String[] args) {
                    NetworkResetFixture fixture = new NetworkResetFixture();
                    fixture.read(true, 42L);
                    if (!Long.valueOf(42L).equals(fixture.network))
                        throw new AssertionError("seed network id was not loaded");

                    fixture.read(false, 0L);
                    if (fixture.network != null)
                        throw new AssertionError("absent network id retained stale value " + fixture.network);
                }
            }
            """.trimIndent()
        )

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)
        val transformed = sourceFile.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "network-stale-network-null-assign" })
        assertTrue(transformed.contains("network = null;"), transformed)

        val classes = tempDir.resolve("classes").also { it.createDirectories() }
        val diagnostics = ByteArrayOutputStream()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val exitCode = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-d",
            classes.toString(),
            sourceFile.toString()
        )
        assertEquals(0, exitCode, diagnostics.toString())

        URLClassLoader(arrayOf(classes.toUri().toURL())).use { loader ->
            loader.loadClass("com.example.NetworkResetFixture")
                .getMethod("main", Array<String>::class.java)
                .invoke(null, emptyArray<String>())
        }
    }
}
