package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class InheritedAndLibraryValuesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `library item entries and inherited handler fields carry their proven value types`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        root.resolve("Catalog.java").writeText("""
            package example;
            import com.tterrag.registrate.util.entry.ItemEntry;
            import net.minecraft.world.item.Item;
            class Catalog { public static ItemEntry<Item> EXAMPLE; }
        """.trimIndent())
        root.resolve("Parent.java").writeText("""
            package example;
            import net.minecraft.world.entity.player.Player;
            import net.neoforged.neoforge.items.ItemStackHandler;
            class Parent<T> {
                protected Player player;
                protected ItemStackHandler inventory;
            }
        """.trimIndent())
        val file = root.resolve("Child.java")
        file.writeText("""
            package example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            class Child extends Parent<String> {
                CompoundTag item(Level level) { return Catalog.EXAMPLE.asStack().serializeNBT(); }
                CompoundTag handler(Level level) { return inventory.serializeNBT(); }
                CompoundTag own(Level level) {
                    Other inventory = new Other();
                    return inventory.serializeNBT();
                }
            }
            class Other { public CompoundTag serializeNBT() { return new CompoundTag(); } }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("Catalog.EXAMPLE.asStack().saveOptional(level.registryAccess())"), source)
        assertTrue(source.contains("inventory.serializeNBT(level.registryAccess())"), source)
        assertTrue(source.contains("return inventory.serializeNBT();"), source)
    }
}
