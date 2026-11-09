package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class VanillaConstructorMovesTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `door superclass arguments change order and bounded nbt accounting keeps its quota`() {
        val file = tempDir.resolve("src/main/java/com/example/Panel.java")
        file.parent.createDirectories()
        file.writeText("""
            package com.example;

            import net.minecraft.nbt.NbtAccounter;
            import net.minecraft.world.level.block.DoorBlock;
            import net.minecraft.world.level.block.state.properties.BlockSetType;

            public class Panel extends DoorBlock {
                public Panel(Properties settings, BlockSetType kind) {
                    super(settings, kind);
                }

                public NbtAccounter budget(long bytes) {
                    return new NbtAccounter(Math.max(1, bytes));
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val result = file.readText()
        assertTrue(result.contains("super(kind, settings)"), result)
        assertTrue(result.contains("NbtAccounter.create(Math.max(1, bytes))"), result)
    }

    @Test
    fun `an unrelated superclass and explicit target nbt depth retain their arguments`() {
        val file = tempDir.resolve("src/main/java/com/example/Other.java")
        file.parent.createDirectories()
        file.writeText("""
            package com.example;

            import net.minecraft.nbt.NbtAccounter;

            public class Other extends CustomDoorBlock {
                public Other(Properties settings, Kind kind) {
                    super(settings, kind);
                }

                public NbtAccounter budget(long bytes) {
                    return new NbtAccounter(bytes, 64);
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val result = file.readText()
        assertTrue(result.contains("super(settings, kind)"), result)
        assertTrue(result.contains("new NbtAccounter(bytes, 64)"), result)
    }
}
