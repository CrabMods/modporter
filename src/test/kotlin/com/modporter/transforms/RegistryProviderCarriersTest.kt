package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryProviderCarriersTest {
    @TempDir lateinit var tempDir: Path

    private fun java(name: String, source: String): Path = tempDir.resolve("src/main/java/com/example/$name.java").also {
        it.parent.createDirectories()
        it.writeText(source.trimIndent())
    }

    @Test
    fun `provider discovery follows a base hook before reaching callers which do not name tags`() {
        java("Policy", """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public abstract class Policy {
                public static Policy read(CompoundTag tag) {
                    Policy result = new Concrete();
                    result.readAdditional(tag);
                    return result;
                }
                protected abstract void readAdditional(CompoundTag tag);
            }
        """)
        java("Concrete", """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            public class Concrete extends Policy {
                ItemStack icon;
                @Override
                protected void readAdditional(CompoundTag tag) {
                    icon = ItemStack.of(tag.getCompound("Icon"));
                }
            }
        """)
        java("Sheet", """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class Sheet {
                Policy policy;
                public static Sheet read(CompoundTag tag) {
                    Sheet sheet = new Sheet();
                    sheet.policy = Policy.read(tag);
                    return sheet;
                }
            }
        """)
        val item = java("SheetItem", """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            public class SheetItem {
                public static Sheet getSheet(ItemStack stack) {
                    return Sheet.read(stack.getTagElement("Sheet"));
                }
            }
        """)
        val reader = java("Reader", """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            public class Reader {
                public Sheet get(Level level, ItemStack stack) {
                    return SheetItem.getSheet(stack);
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val itemText = item.readText()
        val provider = Regex(
            """getSheet\(ItemStack stack, (?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+)\)"""
        ).find(itemText)?.groupValues?.get(1)
        assertTrue(provider != null, itemText)
        assertTrue(itemText.contains("Sheet.read(getCustomDataChild(stack, \"Sheet\"), $provider)"), itemText)
        val readerText = reader.readText()
        assertTrue(readerText.contains("SheetItem.getSheet(stack, level.registryAccess())"), readerText)
    }

    @Test
    fun `a reflected method reads registries through an inherited field with a block entity type bound`() {
        java("Owner", """
            package com.example;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public abstract class Owner<T extends BlockEntity> {
                protected final T anchor;
                public Owner(T anchor) {
                    this.anchor = anchor;
                }
            }
        """)
        val file = java("Reader", """
            package com.example;
            import dan200.computercraft.api.lua.LuaFunction;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public class Reader extends Owner<BlockEntity> {
                public Reader(BlockEntity anchor) {
                    super(anchor);
                }
                @LuaFunction
                public CompoundTag data(ItemStack stack) {
                    java.util.Objects.requireNonNull(anchor.getLevel());
                    return stack.serializeNBT();
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("data(ItemStack stack)"), result)
        assertTrue(result.contains("stack.saveOptional(anchor.getLevel().registryAccess())"), result)
        assertFalse(result.contains("data(ItemStack stack, HolderLookup.Provider"), result)
    }

    @Test
    fun `a player interaction event supplies the live entity registries without widening its listener`() {
        val file = java("Interactions", """
            package com.example;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            public class Interactions {
                @SubscribeEvent
                public static void capture(PlayerInteractEvent.RightClickBlock event) {
                    ItemStack held = event.getItemStack();
                    CompoundTag tag = held.serializeNBT();
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("capture(PlayerInteractEvent.RightClickBlock event)"), result)
        assertTrue(result.contains("held.saveOptional(event.getEntity().registryAccess())"), result)
    }

    @Test
    fun `a same named project event does not inherit a platform registry accessor`() {
        java("RightClickBlock", """
            package com.example;
            public class RightClickBlock {
                public String getEntity() { return "name"; }
            }
        """)
        val file = java("Interactions", """
            package com.example;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            public class Interactions {
                @SubscribeEvent
                public static void capture(RightClickBlock event, ItemStack held) {
                    CompoundTag tag = held.serializeNBT();
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("held.serializeNBT()"), result)
        assertFalse(result.contains("event.getEntity().registryAccess()"), result)
    }
}
