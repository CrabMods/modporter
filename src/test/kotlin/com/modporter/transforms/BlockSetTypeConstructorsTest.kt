package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockSetTypeConstructorsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `new block set properties use target defaults while legacy arguments remain evaluated once`() {
        val file = directory.resolve("src/main/java/example/DoorSounds.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.world.level.block.state.properties.BlockSetType;
            class DoorSounds {
                BlockSetType make() {
                    return new BlockSetType(nextName(), hand(), surface(), close(), open(),
                        trapClose(), trapOpen(), pressureOff(), pressureOn(), buttonOff(), buttonOn());
                }
            }
        """.trimIndent())
        StructuralRefactorPass().apply(directory)
        val result = file.readText()
        assertTrue(result.contains("modporterBlockSetType(nextName(), hand(), surface(), close(), open(),"), result)
        assertTrue(result.contains("defaults.canOpenByWindCharge()"), result)
        assertTrue(result.contains("defaults.canButtonBeActivatedByArrows()"), result)
        assertTrue(result.contains("defaults.pressurePlateSensitivity()"), result)
        assertEquals(1, Regex("nextName\\(\\)").findAll(result).count())
        StructuralRefactorPass().apply(directory)
        assertEquals(result, file.readText())
    }

    @Test
    fun `unrelated local type and already migrated constructors remain intact`() {
        val file = directory.resolve("src/main/java/example/BlockSetType.java")
        file.parent.createDirectories()
        val source = """
            package example;
            class BlockSetType {
                Object make() { return new BlockSetType(a, b, c, d, e, f, g, h, i, j, k); }
            }
        """.trimIndent()
        file.writeText(source)
        StructuralRefactorPass().apply(directory)
        assertEquals(source, file.readText())
    }
}
