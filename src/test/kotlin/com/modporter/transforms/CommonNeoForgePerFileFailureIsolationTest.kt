package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCommonNeoForge121Apis
import com.modporter.core.transforms.structural.walkJavaSourceRoots
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommonNeoForgePerFileFailureIsolationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `unsupported spawn override does not stop a later supported file`() {
        val sourceDir = tempDir.resolve("src/main/java/example")
        sourceDir.createDirectories()
        val candidateFiles = listOf(
            sourceDir.resolve("FirstBlock.java"),
            sourceDir.resolve("SecondBlock.java")
        )
        candidateFiles.forEach { it.writeText("// traversal placeholder\n") }

        val pass = StructuralRefactorPass()
        val traversalOrder = pass.walkJavaSourceRoots(tempDir.resolve("src/main/java")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".java") }.toList()
        }
        assertEquals(candidateFiles.toSet(), traversalOrder.toSet())
        assertEquals(2, traversalOrder.size)

        val unsupportedFile = traversalOrder[0]
        val supportedFile = traversalOrder[1]
        val unsupportedSource = blockSource(
            unsupportedFile.fileName.toString().removeSuffix(".java"),
            "type == Type.ON_GROUND"
        )
        val supportedInput = blockSource(
            supportedFile.fileName.toString().removeSuffix(".java"),
            "false"
        )
        unsupportedFile.writeText(unsupportedSource)
        supportedFile.writeText(supportedInput)

        val errors = mutableListOf<String>()
        val changes = pass.migrateCommonNeoForge121Apis(
            projectDir = tempDir,
            dryRun = false,
            errorSink = errors
        )
        val supportedSource = supportedFile.readText()
        val unsupportedRelativePath = tempDir.relativize(unsupportedFile)
            .toString()
            .replace('\\', '/')

        assertEquals(
            listOf(
                "Common NeoForge 1.21 API migration error in $unsupportedRelativePath: " +
                    "Legacy Block isValidSpawn predicate depends on state unavailable before super(): Type, type"
            ),
            errors
        )
        assertEquals(unsupportedSource, unsupportedFile.readText())
        assertTrue(changes.isNotEmpty(), "The supported file must report its migration")
        assertTrue(changes.all { it.file == supportedFile }, changes.joinToString("\n"))
        assertTrue(
            supportedSource.contains(
                "super(properties.isValidSpawn((state, level, pos, entityType) -> false));"
            ),
            supportedSource
        )
        assertFalse(supportedSource.contains("boolean isValidSpawn("), supportedSource)
    }

    private fun blockSource(className: String, returnExpression: String): String = """
        package example;

        import net.minecraft.core.BlockPos;
        import net.minecraft.world.entity.EntityType;
        import net.minecraft.world.entity.SpawnPlacements.Type;
        import net.minecraft.world.level.BlockGetter;
        import net.minecraft.world.level.block.Block;
        import net.minecraft.world.level.block.state.BlockState;

        public class $className extends Block {
            public $className(Properties properties) {
                super(properties);
            }

            @Override
            public boolean isValidSpawn(BlockState state, BlockGetter level, BlockPos pos, Type type,
                                        EntityType<?> entityType) {
                return $returnExpression;
            }
        }
    """.trimIndent()
}
