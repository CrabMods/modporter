package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyPlayerWillDestroyWithoutSuper
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LegacyBlockReturnFlowsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `playerWillDestroy without super returns incoming state on every original exit`() {
        val source = """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            abstract class ClipboardBase extends Block {}
            public class ClipboardBlock extends ClipboardBase {
                @Override
                public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                    Runnable nested = () -> {
                        return;
                    };
                    if (!hasClipboard(level, pos))
                        return;
                    if (level.isClientSide || player.isCreative())
                        return;
                    dropClipboard(level, pos);
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyPlayerWillDestroyWithoutSuper(source)
        val method = migrated.substring(migrated.indexOf("playerWillDestroy"))

        assertTrue(Regex("""if\s*\(!hasClipboard\(level, pos\)\)\s*return state;""").containsMatchIn(method), migrated)
        assertTrue(Regex("""if\s*\(level\.isClientSide \|\| player\.isCreative\(\)\)\s*return state;""").containsMatchIn(method), migrated)
        assertTrue(method.trimEnd().contains("dropClipboard(level, pos);\n        return state;"), migrated)
        assertTrue(migrated.contains("Runnable nested = () -> {\n            return;"), migrated)
    }

    @Test
    fun `playerWillDestroy with super or a different value return stays unchanged`() {
        val withSuper = """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class BlockWithSuper extends Block {
                @Override
                public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                    if (level.isClientSide) return;
                    return super.playerWillDestroy(level, pos, state, player);
                }
            }
        """.trimIndent()
        val otherValue = withSuper
            .replace("class BlockWithSuper", "class BlockWithOtherValue")
            .replace("return super.playerWillDestroy(level, pos, state, player);", "return replacementState();")

        assertEquals(withSuper, passFor(withSuper).migrateLegacyPlayerWillDestroyWithoutSuper(withSuper))
        assertEquals(otherValue, passFor(otherValue).migrateLegacyPlayerWillDestroyWithoutSuper(otherValue))
    }

    @Test
    fun `project interface override with block signature stays byte identical`() {
        val source = """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            interface DestructionCallback {
                BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player);
            }
            public class ClipboardBehavior implements DestructionCallback {
                @Override
                public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                    if (level.isClientSide)
                        return;
                    dropClipboard(level, pos);
                }
            }
        """.trimIndent()

        assertEquals(source, passFor(source).migrateLegacyPlayerWillDestroyWithoutSuper(source))
    }

    private fun passFor(source: String): StructuralRefactorPass {
        val file = directory.resolve("Source-${source.hashCode()}.java")
        file.writeText(source)
        return StructuralRefactorPass().also { pass ->
            pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(file))
        }
    }
}
