package com.modporter.transforms

import com.modporter.core.transforms.structural.RecipeWrapperHandlers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeWrapperHandlersTest {
    @Test
    fun `same wrapper field names resolve only inside their declaring classes`() {
        val source = """
            package example;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.items.ItemStackHandler;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

            class NamedBacking {
                static final ItemStackHandler BACKING = new ItemStackHandler(1);
                static final RecipeWrapper INPUT = new RecipeWrapper(BACKING);
                void fill(ItemStack stack) {
                    INPUT.setItem(0, stack);
                }
            }
            class InlineBacking {
                static final RecipeWrapper INPUT =
                    new RecipeWrapper(new ItemStackHandler(2));
                void fill(ItemStack stack) {
                    INPUT.setItem(1, stack);
                }
            }
            class UnknownBacking {
                static final RecipeWrapper INPUT = createInput();
                static RecipeWrapper createInput() {
                    return new RecipeWrapper(new ItemStackHandler(1));
                }
                void fill(ItemStack stack) {
                    INPUT.setItem(0, stack);
                }
            }
        """.trimIndent()

        val result = RecipeWrapperHandlers.migrate(source)

        assertTrue(result.contains("BACKING.setStackInSlot(0, stack);"), result)
        assertTrue(result.contains("static final ItemStackHandler INPUT_HANDLER = new ItemStackHandler(2);"), result)
        assertTrue(result.contains("INPUT_HANDLER.setStackInSlot(1, stack);"), result)
        assertFalse(result.contains("BACKING.setStackInSlot(1, stack);"), result)
        assertTrue(result.contains("class UnknownBacking"), result)
        assertTrue(result.contains("INPUT.setItem(0, stack);"), result)
    }

    @Test
    fun `project lookalikes without canonical imports remain byte identical`() {
        val source = """
            package example;
            class ItemStackHandler {
                ItemStackHandler(int size) {}
            }
            class RecipeWrapper {
                RecipeWrapper(ItemStackHandler handler) {}
                void setItem(int slot, Object value) {}
            }
            class Queries {
                static final RecipeWrapper INPUT =
                    new RecipeWrapper(new ItemStackHandler(1));
                void fill(Object value) {
                    INPUT.setItem(0, value);
                }
            }
        """.trimIndent()

        assertEquals(source, RecipeWrapperHandlers.migrate(source))
    }
}
