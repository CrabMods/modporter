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

class LegacyPlantablesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `arbitrary legacy plant behavior is reported and left byte-for-byte unchanged`() {
        val file = directory.resolve("src/main/java/example/Plants.java")
        file.parent.createDirectories()
        val original = """
            package example;
            import net.minecraftforge.common.IPlantable;
            class Plants {
                Object state(Object block, Object level, Object pos) {
                    if (block instanceof IPlantable)
                        return ((IPlantable) block).getPlant(level, pos);
                    return block;
                }
            }
        """.trimIndent()
        file.writeText(original)

        val receipt = StructuralRefactorPass().apply(directory)

        assertTrue(
            receipt.errors.any {
                it.contains("Lossless IPlantable migration is unavailable") &&
                    it.contains("source left unchanged")
            },
            receipt.errors.joinToString("\n")
        )
        assertEquals(original, file.readText())
    }

    @Test
    fun `a project-owned same-name interface is not treated as Forge IPlantable`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        root.resolve("IPlantable.java").writeText("""
            package example;
            interface IPlantable {
                Object plant();
            }
        """.trimIndent())
        val use = root.resolve("Use.java")
        val original = """
            package example;
            class Use {
                Object plant(IPlantable value) {
                    return value.plant();
                }
            }
        """.trimIndent()
        use.writeText(original)

        val receipt = StructuralRefactorPass().apply(directory)

        assertTrue(
            receipt.errors.none { it.contains("Lossless IPlantable migration is unavailable") },
            receipt.errors.joinToString("\n")
        )
        assertEquals(original, use.readText())
    }
}
