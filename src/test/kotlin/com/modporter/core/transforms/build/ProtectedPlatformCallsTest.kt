package com.modporter.core.transforms.build

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

class ProtectedPlatformCallsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a protected platform method called on another block goes through a generated invoker`() {
        val source = directory.resolve("src/main/java/example/block/MirrorLeverBlock.java")
        source.parent.createDirectories()
        source.writeText(
            """
            package example.block;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.shapes.CollisionContext;
            import net.minecraft.world.phys.shapes.VoxelShape;

            public class MirrorLeverBlock extends Block {
                public MirrorLeverBlock(Properties properties) { super(properties); }

                @Override
                protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
                    return Blocks.LEVER.getShape(state, level, pos, context);
                }

                String name() { return Blocks.LEVER.getDescriptionId(); }
            }
            """.trimIndent() + "\n"
        )
        val resources = directory.resolve("src/main/resources")
        resources.createDirectories()
        val config = resources.resolve("example.mixins.json")
        config.writeText(
            """
            {
              "required": true,
              "package": "example.mixin",
              "compatibilityLevel": "JAVA_21",
              "mixins": [],
              "client": []
            }
            """.trimIndent() + "\n"
        )
        val modsToml = resources.resolve("META-INF/neoforge.mods.toml")
        modsToml.parent.createDirectories()
        modsToml.writeText("modLoader=\"javafml\"\n\n[[mixins]]\nconfig=\"example.mixins.json\"\n")

        val changes = ProtectedPlatformCalls.migrate(directory, dryRun = false)

        val migrated = source.readText()
        assertTrue(
            migrated.contains(
                "return ((example.mixin.modporter.ModPorterBlockBehaviourInvoker) (Object) Blocks.LEVER).modporter\$invokeGetShape(state, level, pos, context);"
            ),
            migrated
        )
        // a public method keeps its direct call
        assertTrue(migrated.contains("return Blocks.LEVER.getDescriptionId();"), migrated)
        val invoker = directory.resolve("src/main/java/example/mixin/modporter/ModPorterBlockBehaviourInvoker.java")
        assertTrue(invoker.exists())
        val invokerText = invoker.readText()
        assertTrue(invokerText.contains("@Mixin(value = net.minecraft.world.level.block.state.BlockBehaviour.class, remap = false)"), invokerText)
        assertTrue(invokerText.contains("@Invoker(value = \"getShape\", remap = false)"), invokerText)
        assertTrue(
            invokerText.contains(
                "net.minecraft.world.phys.shapes.VoxelShape modporter\$invokeGetShape(net.minecraft.world.level.block.state.BlockState arg0, " +
                    "net.minecraft.world.level.BlockGetter arg1, net.minecraft.core.BlockPos arg2, net.minecraft.world.phys.shapes.CollisionContext arg3);"
            ),
            invokerText
        )
        assertTrue(config.readText().contains("\"modporter.ModPorterBlockBehaviourInvoker\""), config.readText())
        assertTrue(changes.any { it.ruleId == ProtectedPlatformCalls.RULE_ID })

        val snapshot = migrated
        assertTrue(ProtectedPlatformCalls.migrate(directory, dryRun = false).isEmpty())
        assertEquals(snapshot, source.readText())
    }

    @Test
    fun `without a mixin config owning the caller the call stays visible`() {
        val source = directory.resolve("src/main/java/example/Plain.java")
        source.parent.createDirectories()
        val text = """
            package example;

            import net.minecraft.world.level.block.Blocks;

            class Plain {
                Object shape(Object state) { return Blocks.LEVER.getShape(null, null, null, null); }
            }
        """.trimIndent() + "\n"
        source.writeText(text)

        assertTrue(ProtectedPlatformCalls.migrate(directory, dryRun = false).isEmpty())
        assertEquals(text, source.readText())
        assertFalse(directory.resolve("src/main/java/example/mixin").exists())
    }
}
