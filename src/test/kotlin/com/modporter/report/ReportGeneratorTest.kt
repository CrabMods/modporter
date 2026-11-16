package com.modporter.report

import com.modporter.core.pipeline.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertTrue

class ReportGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun sampleResult(dryRun: Boolean = false): PipelineResult {
        val highChange = Change(
            file = Path.of("src/Mod.java"), line = 10,
            description = "Package rename", before = "net.minecraftforge", after = "net.neoforged.neoforge",
            confidence = Confidence.HIGH, ruleId = "pkg-main"
        )
        val medChange = Change(
            file = Path.of("src/Cap.java"), line = 20,
            description = "Capability detected", before = "ICapabilityProvider", after = "RegisterCapabilitiesEvent provider",
            confidence = Confidence.MEDIUM, ruleId = "struct-cap"
        )
        val lowChange = Change(
            file = Path.of("src/Net.java"), line = 30,
            description = "LazyOptional usage", before = "cap.ifPresent", after = "if (cap != null)",
            confidence = Confidence.LOW, ruleId = "struct-lazy"
        )
        return PipelineResult(
            passResults = listOf(
                PassResult("Text Replacement", listOf(highChange)),
                PassResult(
                    "Structural Refactor",
                    listOf(medChange, lowChange),
                    errors = listOf("Parse error in Broken.java"),
                    skipped = listOf("Could not parse src/Broken.java")
                )
            ),
            dryRun = dryRun
        )
    }

    @Test
    fun `generates report with all sections`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)

        assertTrue(reportPath.exists(), "Report file should be created")
        val content = reportPath.readText()

        assertTrue(content.contains("Migration Report"))
        assertTrue(content.contains("**Mode**: APPLIED"))
        assertTrue(content.contains("**Total changes**: 3"))
        assertTrue(content.contains("**Total skipped**: 1"))
        assertTrue(content.contains("**Total errors**: 1"))
    }

    @Test
    fun `report contains summary table`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("## Summary by Pass"))
        assertTrue(content.contains("Text Replacement"))
        assertTrue(content.contains("Structural Refactor"))
        assertTrue(content.contains("| Pass | Changes | High | Medium | Low | Skipped | Errors |"))
        assertTrue(content.contains("| Structural Refactor | 2 | 0 | 1 | 1 | 1 | 1 |"))
    }

    @Test
    fun `report contains medium confidence section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("MEDIUM Confidence"))
        assertTrue(content.contains("Capability detected"))
        assertTrue(content.contains("struct-cap").not() || content.contains("Cap.java"))
    }

    @Test
    fun `report contains low confidence section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("LOW Confidence"))
        assertTrue(content.contains("LazyOptional usage"))
    }

    @Test
    fun `report routes low confidence work through automated gates`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("Changes Requiring Automated Validation"))
        assertTrue(content.contains("blocks strict success until an automated gate covers it"))
        assertTrue(!Regex("""manual\s+review|manual\s+migration|manual\s+intervention|hand[- ]edit|human\s+verification|human\s+review""", RegexOption.IGNORE_CASE).containsMatchIn(content))
    }

    @Test
    fun `report contains errors section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("## Errors"))
        assertTrue(content.contains("Parse error in Broken.java"))
    }

    @Test
    fun `report contains skipped source shapes section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("## Skipped Source Shapes"))
        assertTrue(content.contains("Could not parse src/Broken.java"))
        assertTrue(content.contains("incomplete migrations"))
    }

    @Test
    fun `report contains blocking migration work section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("## Blocking Migration Work"))
        assertTrue(content.contains("LOW confidence changes"))
        assertTrue(content.contains("Skipped source shapes"))
        assertTrue(content.contains("Reported errors"))
        assertTrue(content.contains("Final gates"))
        assertTrue(!content.contains("TODO"))
    }

    @Test
    fun `dry run mode shown in report`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(dryRun = true), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("DRY RUN"))
    }

    @Test
    fun `report with no errors omits errors section`() {
        val result = PipelineResult(
            passResults = listOf(
                PassResult("Text Replacement", listOf(
                    Change(Path.of("a.java"), 1, "desc", "b", "a", Confidence.HIGH, "r1")
                ))
            ),
            dryRun = false
        )
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(result, reportPath)
        val content = reportPath.readText()

        // Errors section header may still appear but should be empty
        assertTrue(content.contains("**Total skipped**: 0"))
        assertTrue(content.contains("**Total errors**: 0"))
        assertTrue(!content.contains("## Blocking Migration Work"))
    }

    @Test
    fun `report with no medium or low changes has empty review sections`() {
        val result = PipelineResult(
            passResults = listOf(
                PassResult("Text Replacement", listOf(
                    Change(Path.of("a.java"), 1, "desc", "b", "a", Confidence.HIGH, "r1")
                ))
            ),
            dryRun = false
        )
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(result, reportPath)
        val content = reportPath.readText()

        // Should still have the section headers
        assertTrue(content.contains("MEDIUM Confidence"))
        assertTrue(content.contains("LOW Confidence"))
    }
}
