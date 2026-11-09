package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class EntityNbtProvidersTest {
    @TempDir lateinit var directory: Path
    @Test
    fun `local entity nbt uses the entity registry and non entity methods remain intact`() {
        val file = directory.resolve("src/main/java/example/Save.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.vehicle.MinecartFurnace;
            class Save {
                CompoundTag save(MinecartFurnace cart) { return cart.serializeNBT(); }
                void read(MinecartFurnace cart, CompoundTag tag) { cart.deserializeNBT(tag); }
                CompoundTag other(Other object) { return object.serializeNBT(); }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        assertTrue(file.readText().contains("cart.serializeNBT(cart.registryAccess())"), file.readText())
        assertTrue(file.readText().contains("cart.deserializeNBT(cart.registryAccess(), tag)"), file.readText())
        assertTrue(file.readText().contains("object.serializeNBT()"), file.readText())
    }
}
