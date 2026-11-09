package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class FinalizedVanillaHooksTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `entity eating and widget rendering retain their bodies in overridable target hooks`() {
        val file = directory.resolve("src/main/java/example/Hooks.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.components.AbstractWidget;
            class Consumer extends LivingEntity {
                @Override public ItemStack eat(Level level, ItemStack food) { return super.eat(level, food); }
            }
            class Light extends AbstractWidget {
                @Override public void render(GuiGraphics graphics, int x, int y, float delta) { draw(graphics); }
            }
            class Other {
                @Override public void render(GuiGraphics graphics, int x, int y, float delta) { draw(graphics); }
            }
        """.trimIndent())
        StructuralRefactorPass().apply(directory)
        val source = file.readText()
        assertTrue(source.contains("eat(Level level, ItemStack food, net.minecraft.world.food.FoodProperties modporterFoodProperties)"), source)
        assertTrue(source.contains("super.eat(level, food, modporterFoodProperties)"), source)
        assertTrue(source.contains("void renderWidget(GuiGraphics graphics, int x, int y, float delta)"), source)
        assertTrue(source.contains("void render(GuiGraphics graphics, int x, int y, float delta)"), source)
    }

    @Test
    fun `hanging entity box computation returns the source box and retains initialization guards`() {
        val file = directory.resolve("src/main/java/example/Poster.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.world.entity.decoration.HangingEntity;
            import net.minecraft.world.phys.AABB;
            class Poster extends HangingEntity {
                @Override protected void recalculateBoundingBox() {
                    if (direction == null) return;
                    this.setBoundingBox(new AABB(x, y, z, x + getWidth(), y + getHeight(), z + 1));
                }
            }
        """.trimIndent())
        StructuralRefactorPass().apply(directory)
        val source = file.readText()
        assertTrue(source.contains("calculateBoundingBox(net.minecraft.core.BlockPos modporterAnchor, net.minecraft.core.Direction modporterFacing)"), source)
        assertTrue(source.contains("return this.getBoundingBox()"), source)
        assertTrue(source.contains("return new AABB(x, y, z, x + getWidth(), y + getHeight(), z + 1)"), source)
    }
}
