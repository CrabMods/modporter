package com.modporter.integration

import com.modporter.core.pipeline.*
import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compilation verification tests using Gradle TestKit.
 *
 * These tests convert synthetic Forge 1.20.1 source code through the Forge2Neo pipeline,
 * inject the output into a NeoForge 1.21.1 MDK template, and attempt `compileJava`
 * to verify the converted code actually compiles.
 *
 * REQUIREMENTS:
 * - Java 21 JDK installed (NeoForge 1.21.1 requires it)
 * - Internet access (first run downloads NeoForge dependencies ~500MB)
 * - Set environment variable FORGE2NEO_COMPILE_TEST=true to enable
 *
 * These tests are DISABLED by default because they:
 * - Require Java 21 (our build tool uses Java 17)
 * - Download large dependencies on first run
 * - Take 1-5 minutes per test
 *
 * Enable with: FORGE2NEO_COMPILE_TEST=true ./gradlew testKitTest
 */
@EnabledIfEnvironmentVariable(named = "FORGE2NEO_COMPILE_TEST", matches = "true")
class CompilationVerificationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var mdkTemplate: Path

    @BeforeEach
    fun setUp() {
        // Copy MDK template from test resources
        mdkTemplate = tempDir.resolve("mdk-template")
        val templateResource = javaClass.getResource("/neoforge-mdk-template")
            ?: fail("MDK template not found in test resources")

        val templatePath = Path.of(templateResource.toURI())
        templatePath.toFile().copyRecursively(mdkTemplate.toFile())
    }

    private fun runPipeline(projectDir: Path): PipelineResult {
        val mappingDb = MappingDatabase.loadDefault()
        return Pipeline(
            passes = listOf(
                TextReplacementPass(mappingDb),
                AstTransformPass(mappingDb),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(mappingDb)
            ),
            dryRun = false
        ).run(projectDir)
    }

    /**
     * Create a NeoForge project with converted source and attempt compilation.
     * Returns the GradleRunner build result.
     */
    private fun compileConverted(
        forgeSourceFiles: Map<String, String>,
        modId: String = "testmod"
    ): CompilationResult {
        // 1. Create a Forge project with the source files
        val forgeProject = tempDir.resolve("forge-input")
        for ((relativePath, content) in forgeSourceFiles) {
            val file = forgeProject.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(content)
        }

        // 2. Run Forge2Neo pipeline
        val pipelineResult = runPipeline(forgeProject)

        // 3. Copy converted source files into MDK template
        val neoProject = tempDir.resolve("neo-project")
        mdkTemplate.toFile().copyRecursively(neoProject.toFile())

        // Copy converted Java files
        val forgeSrcDir = forgeProject.resolve("src/main/java")
        if (forgeSrcDir.exists()) {
            val neoSrcDir = neoProject.resolve("src/main/java")
            forgeSrcDir.toFile().copyRecursively(neoSrcDir.toFile(), overwrite = true)
        }

        // 4. Attempt compilation via Gradle TestKit
        val buildResult = try {
            GradleRunner.create()
                .withProjectDir(neoProject.toFile())
                .withArguments("compileJava", "--stacktrace")
                .forwardOutput()
                .build()
        } catch (e: UnexpectedBuildFailure) {
            val output = e.buildResult.output
            return CompilationResult(
                success = false,
                pipelineResult = pipelineResult,
                output = output,
                errors = parseCompilationErrors(output)
            )
        } catch (e: Exception) {
            // Build failed — capture the failure
            val output = e.message ?: e.toString()
            return CompilationResult(
                success = false,
                pipelineResult = pipelineResult,
                output = output,
                errors = parseCompilationErrors(output)
            )
        }

        val compileTask = buildResult.task(":compileJava")
        return CompilationResult(
            success = compileTask?.outcome == TaskOutcome.SUCCESS ||
                      compileTask?.outcome == TaskOutcome.UP_TO_DATE,
            pipelineResult = pipelineResult,
            output = buildResult.output,
            errors = parseCompilationErrors(buildResult.output)
        )
    }

    private fun parseCompilationErrors(output: String): List<CompilationError> {
        val errors = mutableListOf<CompilationError>()
        val errorPattern = Regex("""(.+\.java):(\d+): error: (.+)""")

        for (line in output.lines()) {
            val match = errorPattern.find(line)
            if (match != null) {
                val file = match.groupValues[1]
                val lineNum = match.groupValues[2].toIntOrNull() ?: 0
                val message = match.groupValues[3]
                errors.add(CompilationError(
                    file = file,
                    line = lineNum,
                    message = message,
                    category = classifyError(message)
                ))
            }
        }
        return errors
    }

    private fun classifyError(message: String): ErrorCategory = when {
        message.contains("package") && message.contains("does not exist") -> ErrorCategory.WRONG_PACKAGE
        message.contains("cannot find symbol") && message.contains("class") -> ErrorCategory.MISSING_TYPE
        message.contains("cannot find symbol") && message.contains("method") -> ErrorCategory.MISSING_METHOD
        message.contains("incompatible types") -> ErrorCategory.TYPE_MISMATCH
        message.contains("does not override") -> ErrorCategory.OVERRIDE_ERROR
        message.contains("constructor") && message.contains("cannot be applied") -> ErrorCategory.CONSTRUCTOR_CHANGE
        else -> ErrorCategory.UNKNOWN
    }

    // ─── Test Cases ───────────────────────────────────────────────────────

    @Test
    fun `basic mod with package renames compiles`() {
        val result = compileConverted(mapOf(
            "src/main/java/com/example/testmod/BasicMod.java" to """
                package com.example.testmod;

                import net.minecraftforge.fml.common.Mod;

                @Mod("testmod")
                public class BasicMod {
                    public BasicMod() {
                        System.out.println("Hello from BasicMod!");
                    }
                }
            """.trimIndent()
        ))

        if (!result.success) {
            println("=== Compilation Errors ===")
            result.errors.forEach { err ->
                println("  [${err.category}] ${err.file}:${err.line}: ${err.message}")
            }
            println("=== Error Summary ===")
            result.errorSummary().forEach { (category, count) ->
                println("  $category: $count")
            }
        }

        assertTrue(result.success, "Basic mod with package renames should compile.\n" +
            "Errors: ${result.errors.map { it.message }}\n" +
            "Build output tail:\n${result.outputTail()}")
    }

    @Test
    fun `event subscriber mod compiles`() {
        val result = compileConverted(mapOf(
            "src/main/java/com/example/testmod/EventHandler.java" to """
                package com.example.testmod;

                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import net.minecraftforge.fml.common.Mod;
                import net.minecraftforge.event.server.ServerStartingEvent;

                @Mod.EventBusSubscriber(modid = "testmod")
                public class EventHandler {
                    @SubscribeEvent
                    public static void onServerStarting(ServerStartingEvent event) {
                        System.out.println("Server starting!");
                    }
                }
            """.trimIndent()
        ))

        if (!result.success) {
            println("=== Event Mod Compilation Errors ===")
            result.errors.forEach { err ->
                println("  [${err.category}] ${err.file}:${err.line}: ${err.message}")
            }
        }

        // This may fail due to event class renames — document what's missing
        if (!result.success) {
            println("=== Missing Rules Needed ===")
            result.errors
                .filter { it.category == ErrorCategory.WRONG_PACKAGE || it.category == ErrorCategory.MISSING_TYPE }
                .forEach { println("  Add rule for: ${it.message}") }
        }

        assertTrue(result.success, "Event subscriber mod should compile.\n" +
            "Errors: ${result.errors.map { it.message }}\n" +
            "Build output tail:\n${result.outputTail()}")
    }

    @Test
    fun `config mod compiles`() {
        val result = compileConverted(mapOf(
            "src/main/java/com/example/testmod/Config.java" to """
                package com.example.testmod;

                import net.minecraftforge.common.ForgeConfigSpec;

                public class Config {
                    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
                    public static final ForgeConfigSpec.IntValue MAX_ITEMS;
                    public static final ForgeConfigSpec SPEC;

                    static {
                        BUILDER.push("general");
                        MAX_ITEMS = BUILDER.defineInRange("maxItems", 64, 1, 1000);
                        BUILDER.pop();
                        SPEC = BUILDER.build();
                    }
                }
            """.trimIndent()
        ))

        if (!result.success) {
            println("=== Config Mod Compilation Errors ===")
            result.errors.forEach { err ->
                println("  [${err.category}] ${err.file}:${err.line}: ${err.message}")
            }
        }

        assertTrue(result.success, "Config mod should compile.\n" +
            "Errors: ${result.errors.map { it.message }}\n" +
            "Build output tail:\n${result.outputTail()}")
    }

    @Test
    fun `compilation error report is generated correctly`() {
        // Even if compilation fails, we should get a structured error report
        val result = compileConverted(mapOf(
            "src/main/java/com/example/testmod/Broken.java" to """
                package com.example.testmod;

                import net.minecraftforge.common.capabilities.ICapabilityProvider;
                import net.minecraftforge.common.util.LazyOptional;

                public class Broken implements ICapabilityProvider {
                    private final LazyOptional<Object> cap = LazyOptional.of(() -> new Object());

                    public <T> LazyOptional<T> getCapability(Object c, Object d) {
                        return cap.cast();
                    }
                }
            """.trimIndent()
        ))

        // This WILL fail — capabilities were completely rewritten in NeoForge
        // But we should get structured error output
        println("=== Capability Mod Error Report ===")
        println("Success: ${result.success}")
        println("Total errors: ${result.errors.size}")
        println("Pipeline changes: ${result.pipelineResult.totalChanges}")
        result.errorSummary().forEach { (category, count) ->
            println("  $category: $count")
        }

        // The structural refactor pass should have flagged these
        val structuralWarnings = result.pipelineResult.passResults
            .filter { it.passName == "Structural Refactor" }
            .flatMap { it.changes }
        println("Structural warnings: ${structuralWarnings.size}")
        structuralWarnings.forEach { println("  [${it.confidence}] ${it.description}") }

        assertTrue(result.pipelineResult.totalChanges > 0, "Pipeline should report changes for capability source")
        assertTrue(
            result.success || result.errors.isNotEmpty() || result.output.contains("BUILD FAILED"),
            "A failed capability compile should produce structured errors or Gradle failure output"
        )
    }

    // ─── Data Classes ─────────────────────────────────────────────────────

    data class CompilationResult(
        val success: Boolean,
        val pipelineResult: PipelineResult,
        val output: String,
        val errors: List<CompilationError>
    ) {
        fun errorSummary(): Map<ErrorCategory, Int> =
            errors.groupBy { it.category }.mapValues { it.value.size }

        fun outputTail(lineCount: Int = 80): String =
            output.lines().takeLast(lineCount).joinToString("\n")
    }

    data class CompilationError(
        val file: String,
        val line: Int,
        val message: String,
        val category: ErrorCategory
    )

    enum class ErrorCategory {
        WRONG_PACKAGE,
        MISSING_TYPE,
        MISSING_METHOD,
        TYPE_MISMATCH,
        OVERRIDE_ERROR,
        CONSTRUCTOR_CHANGE,
        UNKNOWN
    }
}
