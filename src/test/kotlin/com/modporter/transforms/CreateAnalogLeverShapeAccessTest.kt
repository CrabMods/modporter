package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateAnalogLeverShapeAccess
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateAnalogLeverShapeAccessTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `generates the official common invoker and preserves vanilla lever dispatch`() {
        val fixture = fixture(
            "return Blocks.LEVER.getShape(state, worldIn, pos, context);"
        )
        val result = CreateAnalogLeverShapeAccess.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(3, result.changes.size)
        val analog = fixture.analog.readText()
        assertTrue(
            analog.contains(
                "import com.simibubi.create.foundation.mixin.accessor.BlockBehaviourAccessor;"
            ),
            analog
        )
        assertTrue(
            compact(analog).contains(
                "return ((BlockBehaviourAccessor) Blocks.LEVER)." +
                    "create\$getShape(state,worldIn,pos,context);"
            ),
            analog
        )
        assertFalse(analog.contains("state.getShape("), analog)
        assertFalse(analog.contains("Blocks.LEVER.getShape("), analog)
        assertFalse(analog.contains("""@SuppressWarnings("deprecation")"""), analog)

        val accessor = fixture.accessor.readText()
        assertTrue(accessor.contains("@Mixin(BlockBehaviour.class)"), accessor)
        assertTrue(accessor.contains("""@Invoker("getShape")"""), accessor)
        assertTrue(accessor.contains("VoxelShape create\$getShape("), accessor)
        assertTrue(accessor.contains("BlockState blockState"), accessor)
        assertTrue(accessor.contains("BlockGetter blockGetter"), accessor)
        assertTrue(accessor.contains("BlockPos blockPos"), accessor)
        assertTrue(accessor.contains("CollisionContext context"), accessor)

        val config = fixture.config.readText()
        assertTrue(
            Regex(
                """"mixins"\s*:\s*\[[^\]]*"accessor\.BlockBehaviourAccessor"""",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(config),
            config
        )
        assertFalse(
            Regex(
                """"client"\s*:\s*\[[^\]]*"accessor\.BlockBehaviourAccessor"""",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(config),
            config
        )

        val second = CreateAnalogLeverShapeAccess.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )
        assertTrue(second.gates.isEmpty(), second.gates.joinToString())
        assertTrue(second.changes.isEmpty(), second.changes.joinToString())
    }

    @Test
    fun `rejects BlockState shape dispatch because it would recurse into AnalogLever`() {
        val fixture = fixture(
            "return state.getShape(worldIn, pos, context);"
        )
        val originalAnalog = fixture.analog.readText()
        val originalConfig = fixture.config.readText()

        val result = CreateAnalogLeverShapeAccess.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        assertTrue(
            result.gates.any { it.contains("would recurse") },
            result.gates.joinToString()
        )
        assertTrue(result.changes.isEmpty())
        assertEquals(originalAnalog, fixture.analog.readText())
        assertEquals(originalConfig, fixture.config.readText())
        assertFalse(fixture.accessor.exists())
    }

    @Test
    fun `requires one registered common Create mixin owner before writing the family`() {
        val fixture = fixture(
            "return Blocks.LEVER.getShape(state, worldIn, pos, context);",
            commonMixins = false
        )
        val originalAnalog = fixture.analog.readText()
        val originalConfig = fixture.config.readText()

        val result = CreateAnalogLeverShapeAccess.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        assertTrue(
            result.gates.any { it.contains("no common mixins array") },
            result.gates.joinToString()
        )
        assertTrue(result.changes.isEmpty())
        assertEquals(originalAnalog, fixture.analog.readText())
        assertEquals(originalConfig, fixture.config.readText())
        assertFalse(fixture.accessor.exists())
    }

    private fun fixture(
        returnStatement: String,
        commonMixins: Boolean = true
    ): Fixture {
        val analog = directory.resolve(
            "src/main/java/com/simibubi/create/content/redstone/analogLever/" +
                "AnalogLeverBlock.java"
        )
        analog.parent.createDirectories()
        analog.writeText(
            """
            package com.simibubi.create.content.redstone.analogLever;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.shapes.CollisionContext;
            import net.minecraft.world.phys.shapes.VoxelShape;

            public class AnalogLeverBlock extends FaceAttachedHorizontalDirectionalBlock {
                @SuppressWarnings("deprecation")
                @Override
                public VoxelShape getShape(
                    BlockState state,
                    BlockGetter worldIn,
                    BlockPos pos,
                    CollisionContext context
                ) {
                    $returnStatement
                }
            }
            """.trimIndent()
        )

        val config = directory.resolve("src/main/resources/create.mixins.json")
        config.parent.createDirectories()
        val common = if (commonMixins) {
            """
              "mixins": [
                "BlockMixin"
              ],
            """.trimIndent()
        } else {
            ""
        }
        config.writeText(
            """
            {
              "required": true,
              "package": "com.simibubi.create.foundation.mixin",
            $common
              "client": [
                "client.GameRendererMixin"
              ]
            }
            """.trimIndent() + "\n"
        )

        val metadata = directory.resolve(
            "src/main/resources/META-INF/neoforge.mods.toml"
        )
        metadata.parent.createDirectories()
        metadata.writeText(
            """
            [[mixins]]
            config="create.mixins.json"
            """.trimIndent() + "\n"
        )

        return Fixture(
            analog,
            config,
            directory.resolve(
                "src/main/java/com/simibubi/create/foundation/mixin/accessor/" +
                    "BlockBehaviourAccessor.java"
            )
        )
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*,\s*"""), ",")
            .trim()

    private data class Fixture(
        val analog: Path,
        val config: Path,
        val accessor: Path
    )
}
