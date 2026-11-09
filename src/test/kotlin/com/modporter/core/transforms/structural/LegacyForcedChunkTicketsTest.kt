package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyForcedChunkTicketsTest {
    @TempDir
    lateinit var directory: Path

    private fun java(relative: String, source: String): Path =
        directory.resolve("src/main/java").resolve(relative).also {
            it.parent.createDirectories()
            it.writeText(source.trimIndent())
        }

    @Test
    fun `own mod id tickets use one generated ticket controller`() {
        java(
            "example/ExampleMod.java",
            """
            package example;
            public final class ExampleMod {
                public static final String MODID = "example";
            }
            """
        )
        val loader = java(
            "example/block/LoaderBlockEntity.java",
            """
            package example.block;
            import example.ExampleMod;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.core.BlockPos;
            import net.neoforged.neoforge.common.world.ForcedChunkManager;
            public class LoaderBlockEntity {
                void load(ServerLevel level, BlockPos pos, int x, int z) {
                    ForcedChunkManager.forceChunk(level, ExampleMod.MODID, pos, x, z, true, true);
                    ForcedChunkManager.forceChunk(level, "example", pos, x + 1, z, false, true
                    );
                }
            }
            """
        )

        val result = LegacyForcedChunkTickets.migrate(directory, "example", { "com.modporter.generated.example.compat" }, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.toString())
        val migrated = loader.readText()
        assertTrue(
            migrated.contains(
                "com.modporter.generated.example.compat.LegacyForcedChunks.CONTROLLER.forceChunk(level, pos, x, z, true, true);"
            ),
            migrated
        )
        assertTrue(
            migrated.contains("LegacyForcedChunks.CONTROLLER.forceChunk(level, pos, x + 1, z, false, true);"),
            migrated
        )
        assertFalse(migrated.contains("ForcedChunkManager"), migrated)
        val controller = directory.resolve(
            "src/main/java/com/modporter/generated/example/compat/LegacyForcedChunks.java"
        )
        assertTrue(controller.exists())
        assertTrue(controller.readText().contains("ResourceLocation.fromNamespaceAndPath(\"example\", \"forced_chunks\")"))
        assertTrue(controller.readText().contains("event.register(CONTROLLER);"))
    }

    @Test
    fun `another mod id or another member is refused unchanged`() {
        val source = java(
            "example/Loader.java",
            """
            package example;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.core.BlockPos;
            import net.minecraftforge.common.world.ForgeChunkManager;
            public class Loader {
                void load(ServerLevel level, BlockPos pos) {
                    ForgeChunkManager.forceChunk(level, "othermod", pos, 0, 0, true, true);
                }
            }
            """
        )
        val before = source.readText()

        val result = LegacyForcedChunkTickets.migrate(directory, "example", { "com.modporter.generated.example.compat" }, dryRun = false)

        assertTrue(result.gates.any { "othermod" in it }, result.gates.toString())
        assertTrue(result.changes.isEmpty())
        assertEquals(before, source.readText())
    }
}
