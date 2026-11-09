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

class DefaultAttributeTooltipTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `a modifier only item default hides the installed attribute component without changing its modifiers`() {
        val file = directory.resolve("src/main/java/example/Blade.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.world.item.SwordItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            class Blade extends SwordItem {
                Blade(Tier tier, Properties properties) {
                    super(tier, properties.attributes(SwordItem.createAttributes(tier, 3, -2.4F)));
                }
                @Override public int getDefaultTooltipHideFlags(ItemStack stack) { return TooltipPart.MODIFIERS.getMask(); }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("properties.attributes("), source)
        assertTrue(source.contains("SwordItem.createAttributes(tier, 3, -2.4F).withTooltip(false)"), source)
        assertFalse(source.contains("getDefaultAttributeModifiers"), source)
        assertFalse(source.contains("TooltipPart"), source)
    }

    @Test
    fun `an existing stack sensitive modifier getter is preserved while the component flag is changed`() {
        val file = directory.resolve("src/main/java/example/ExistingGetterBlade.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            import net.minecraft.world.item.component.ItemAttributeModifiers;
            class ExistingGetterBlade extends Item {
                ExistingGetterBlade(Item.Properties properties) {
                    super(properties.attributes(defaultModifiers()));
                }
                private static ItemAttributeModifiers defaultModifiers() { return ItemAttributeModifiers.EMPTY; }
                @Override public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
                    return stack.isDamaged() ? damagedModifiers() : defaultModifiers();
                }
                private ItemAttributeModifiers damagedModifiers() { return ItemAttributeModifiers.EMPTY; }
                @Override public int getDefaultTooltipHideFlags(ItemStack stack) { return TooltipPart.MODIFIERS.getMask(); }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("properties.attributes("), source)
        assertTrue(source.contains("defaultModifiers().withTooltip(false)"), source)
        assertTrue(source.contains("return stack.isDamaged() ? damagedModifiers() : defaultModifiers();"), source)
        assertFalse(source.contains("getDefaultTooltipHideFlags"), source)
    }

    @Test
    fun `unknown or incomplete properties constructor chains remain untouched`() {
        val file = directory.resolve("src/main/java/example/UnknownBlade.java")
        file.parent.createDirectories()
        val original = """
            package example;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            class UnknownBlade extends Item {
                UnknownBlade(Item.Properties properties) { super(configure(properties)); }
                private static Item.Properties configure(Item.Properties properties) { return properties; }
                @Override public int getDefaultTooltipHideFlags(ItemStack stack) { return TooltipPart.MODIFIERS.getMask(); }
            }
        """.trimIndent()
        file.writeText(original)
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(
            receipt.errors.any { it.contains("no source-proven Properties.attributes migration") },
            receipt.errors.joinToString("\n")
        )
        assertTrue(file.readText() == original, file.readText())
    }
}
