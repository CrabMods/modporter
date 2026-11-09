package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class LegacyMinecartFactoriesTest {
    @TempDir lateinit var directory: Path
    @Test
    fun `the legacy signature keeps level coordinates and type without inventing item or player values`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        root.resolve("ExampleMod.java").writeText("""
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("example") public class ExampleMod {}
        """.trimIndent())
        val file = root.resolve("Cart.java")
        file.writeText("""
            package example;
            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.minecraft.world.level.Level;
            class Cart {
                Object create(Level level, AbstractMinecart.Type type, Other other) {
                    other.createMinecart(level, 1, 2, 3, type);
                    return AbstractMinecart.createMinecart(level, 1, 2, 3, type);
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        assertTrue(file.readText().contains("LegacyMinecartFactories.create(level, 1, 2, 3, type)"), file.readText())
        assertTrue(file.readText().contains("other.createMinecart(level, 1, 2, 3, type)"), file.readText())
    }
}
