package com.modporter.cli

import com.github.ajalt.clikt.core.UsageError
import com.modporter.AppInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/**
 * Tests for CLI commands by invoking them programmatically.
 * We test the command logic without actually launching a process.
 */
class CliTest {

    @TempDir
    lateinit var tempDir: Path

    private fun setupMiniMod(): Path {
        val projectDir = tempDir.resolve("minimod")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Mini.java").writeText("""
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            public class Mini {
                void init() { MinecraftForge.EVENT_BUS.register(this); }
            }
        """.trimIndent())
        return projectDir
    }

    private fun setupMiniModWithMetadata(): Path {
        val projectDir = setupMiniMod()
        val metaInf = projectDir.resolve("src/main/resources/META-INF")
        metaInf.createDirectories()
        metaInf.resolve("mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"
            [[mods]]
            modId="minimod"
            displayName="Mini Mod"
            authors="Original Author"
            description='''Example'''
            [[dependencies.minimod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())
        return projectDir
    }

    private fun setupBrokenForgeMod(): Path {
        val projectDir = tempDir.resolve("brokenmod")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Broken.java").writeText("""
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            public class Broken {
                void init( {
                    MinecraftForge.EVENT_BUS.register(this);
                }
            }
        """.trimIndent())
        return projectDir
    }

    @Test
    fun `application version metadata comes from Gradle project version`() {
        val buildVersion = Regex("""(?m)^version\s*=\s*"([^"]+)"""")
            .find(Paths.get("build.gradle.kts").readText())
            ?.groupValues
            ?.get(1)
            ?: error("build.gradle.kts project version not found")

        assertEquals(buildVersion, AppInfo.version)
        assertEquals("modporter/$buildVersion", AppInfo.userAgent)
    }

    @Test
    fun `production code does not hardcode rendered tool version surfaces`() {
        val hardcodedVersionSurface = Regex("""(?:ModPorter v|modporter/)\d+\.\d+\.\d+""")
        val offenders = Files.walk(Paths.get("src/main"))
            .filter { Files.isRegularFile(it) }
            .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
            .filter { hardcodedVersionSurface.containsMatchIn(it.readText()) }
            .toList()

        assertTrue(offenders.isEmpty(), "Tool version surfaces must read AppInfo instead of hardcoding: $offenders")
    }

    @Test
    fun `analyze command runs without error`() {
        val projectDir = setupMiniMod()
        val reportPath = tempDir.resolve("analyze-report.md")

        val cmd = AnalyzeCommand()
        cmd.parse(listOf("--src", projectDir.toString(), "--report", reportPath.toString()))

        assertTrue(reportPath.exists(), "Analyze should produce a report")
        val content = reportPath.readText()
        assertTrue(content.contains("Migration Report"))

        // Source file should NOT be modified (analyze = dry run)
        val srcContent = projectDir.resolve("src/main/java/com/example/Mini.java").readText()
        assertTrue(srcContent.contains("net.minecraftforge"), "Analyze should not modify files")
    }

    @Test
    fun `analyze command rejects skipped source shapes after writing report`() {
        val projectDir = setupBrokenForgeMod()
        val reportPath = tempDir.resolve("broken-analyze-report.md")

        val error = assertFailsWith<UsageError> {
            AnalyzeCommand().parse(listOf("--src", projectDir.toString(), "--report", reportPath.toString()))
        }

        assertTrue(error.message?.contains("skipped source shapes") == true)
        assertTrue(reportPath.exists(), "Analyze should still write the blocking report")
        val content = reportPath.readText()
        assertTrue(content.contains("**Total skipped**:"), content)
        assertTrue(content.contains("## Skipped Source Shapes"), content)
    }

    @Test
    fun `validate command detects Forge references`() {
        val projectDir = setupMiniMod()

        val error = assertFailsWith<UsageError> {
            ValidateCommand().parse(listOf("--src", projectDir.toString()))
        }

        assertTrue(error.message?.contains("Validation failed") == true)
    }

    @Test
    fun `validate command on clean NeoForge project`() {
        val projectDir = tempDir.resolve("cleanmod")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Clean.java").writeText("""
            package com.example;
            import net.neoforged.neoforge.common.NeoForge;
            public class Clean {
                void init() { NeoForge.EVENT_BUS.register(this); }
            }
        """.trimIndent())

        val cmd = ValidateCommand()
        cmd.parse(listOf("--src", projectDir.toString(), "--pipeline", "forge2neo"))
        // Should complete without error — no Forge references found
    }

    @Test
    fun `auto pipeline rejects projects without detection evidence`() {
        val projectDir = tempDir.resolve("plainmod")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Plain.java").writeText("""
            package com.example;
            public class Plain {
                void init() {}
            }
        """.trimIndent())

        val error = assertFailsWith<UsageError> {
            AnalyzeCommand().parse(listOf("--src", projectDir.toString()))
        }

        assertTrue(error.message?.contains("No pipeline detected") == true)
    }

    @Test
    fun `auto pipeline detection ignores comments and string literals`() {
        val projectDir = tempDir.resolve("commentonly")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CommentOnly.java").writeText("""
            package com.example;

            public class CommentOnly {
                // import net.minecraftforge.common.MinecraftForge;
                private static final String DOC = "MinecraftForge.EVENT_BUS";
                private static final String BLOCK = ""${'"'}
                    FMLJavaModLoadingContext.get().getModEventBus()
                    ForgeRegistries.ITEMS
                ""${'"'};
            }
        """.trimIndent())

        val error = assertFailsWith<UsageError> {
            AnalyzeCommand().parse(listOf("--src", projectDir.toString()))
        }

        assertTrue(error.message?.contains("No pipeline detected") == true)
    }

    @Test
    fun `auto pipeline detection scans beyond first twenty Java files`() {
        val projectDir = tempDir.resolve("largemod")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        repeat(24) { index ->
            srcDir.resolve("Plain${index.toString().padStart(2, '0')}.java").writeText("""
                package com.example;
                public class Plain${index.toString().padStart(2, '0')} {}
            """.trimIndent())
        }
        srcDir.resolve("ForgeEvidence.java").writeText("""
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            public class ForgeEvidence {
                void init() { MinecraftForge.EVENT_BUS.register(this); }
            }
        """.trimIndent())

        val reportPath = tempDir.resolve("large-analyze-report.md")
        AnalyzeCommand().parse(listOf("--src", projectDir.toString(), "--report", reportPath.toString()))

        assertTrue(reportPath.exists(), "Analyze should detect the pipeline from evidence after the first 20 files")
    }

    @Test
    fun `port command with dry-run does not modify source`() {
        val projectDir = setupMiniMod()
        val originalContent = projectDir.resolve("src/main/java/com/example/Mini.java").readText()

        val cmd = PortCommand()
        cmd.parse(listOf("--src", projectDir.toString(), "--dry-run"))

        val currentContent = projectDir.resolve("src/main/java/com/example/Mini.java").readText()
        assertTrue(originalContent == currentContent, "Dry-run port should not modify source")
    }

    @Test
    fun `port command applies transformations`() {
        val projectDir = setupMiniMod()
        val outDir = tempDir.resolve("minimod-neoforge")
        val reportPath = tempDir.resolve("port-report.md")

        val cmd = PortCommand()
        cmd.parse(listOf(
            "--src", projectDir.toString(),
            "--out", outDir.toString(),
            "--report", reportPath.toString()
        ))

        assertTrue(outDir.exists(), "Output directory should be created")
        val content = outDir.resolve("src/main/java/com/example/Mini.java").readText()
        assertTrue(content.contains("NeoForge.EVENT_BUS"), "Should transform Forge references")
        assertFalse(content.contains("MinecraftForge"), "Old references should be gone")

        assertTrue(reportPath.exists(), "Report should be generated")
    }

    @Test
    fun `port command add tool credit appends metadata credits`() {
        val projectDir = setupMiniModWithMetadata()
        val outDir = tempDir.resolve("credited-neoforge")

        PortCommand().parse(listOf(
            "--src", projectDir.toString(),
            "--out", outDir.toString(),
            "--add-tool-credit"
        ))

        val metadata = outDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(metadata.contains("credits=\"Ported with ModPorter: https://github.com/CrabMods/modporter\""), metadata)
        assertTrue(metadata.contains("authors=\"Original Author\""), metadata)
    }

    @Test
    fun `port command rejects skipped source shapes after writing report`() {
        val projectDir = setupBrokenForgeMod()
        val outDir = tempDir.resolve("brokenmod-neoforge")
        val reportPath = tempDir.resolve("broken-port-report.md")

        val error = assertFailsWith<UsageError> {
            PortCommand().parse(listOf(
                "--src", projectDir.toString(),
                "--out", outDir.toString(),
                "--report", reportPath.toString()
            ))
        }

        assertTrue(error.message?.contains("skipped source shapes") == true)
        assertTrue(reportPath.exists(), "Port should still write the blocking report")
        assertTrue(reportPath.readText().contains("## Skipped Source Shapes"))
    }

    @Test
    fun `port command with confidence filter`() {
        val projectDir = setupMiniMod()

        val cmd = PortCommand()
        cmd.parse(listOf(
            "--src", projectDir.toString(),
            "--dry-run",
            "--min-confidence", "high"
        ))
        // Should complete without error
    }

    @Test
    fun `port command rejects confidence filter in apply mode`() {
        val projectDir = setupMiniMod()
        val outDir = tempDir.resolve("filtered-out")

        val error = assertFailsWith<UsageError> {
            PortCommand().parse(listOf(
                "--src", projectDir.toString(),
                "--out", outDir.toString(),
                "--min-confidence", "high"
            ))
        }

        assertTrue(error.message?.contains("--dry-run") == true)
        assertFalse(outDir.exists(), "Apply mode should fail before creating output")

        val source = projectDir.resolve("src/main/java/com/example/Mini.java").readText()
        assertTrue(source.contains("net.minecraftforge"), "Rejected apply should not modify source")
    }

    @Test
    fun `port command rejects unknown confidence level`() {
        val projectDir = setupMiniMod()

        val error = assertFailsWith<UsageError> {
            PortCommand().parse(listOf(
                "--src", projectDir.toString(),
                "--dry-run",
                "--min-confidence", "certain"
            ))
        }

        assertTrue(error.message?.contains("expected high, medium, or low") == true)
    }

    @Test
    fun `Forge2Neo parent command exists`() {
        val cmd = Forge2Neo()
        cmd.parse(emptyList())
        // Parent command should just be a container
    }
}
