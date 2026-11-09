package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyRecipeWrapperContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class LegacyRecipeWrapperContainersTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `container targets receive the generated item handler adapter`() {
        val sourceRoot = directory.resolve("src/main/java/example")
        sourceRoot.createDirectories()
        val caller = sourceRoot.resolve("Transfer.java")
        caller.writeText(
            """
                package example;

                import net.minecraft.world.Container;
                import net.neoforged.neoforge.items.ItemStackHandler;
                import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

                class Transfer {
                    void create(int size) {
                        Container output = new RecipeWrapper(new ItemStackHandler(9));
                        Container input = new RecipeWrapper(new ItemStackHandler(size));
                        RecipeWrapper recipeInput = new RecipeWrapper(new ItemStackHandler(1));
                    }
                }
            """.trimIndent()
        )
        val lookalike = sourceRoot.resolve("Lookalike.java")
        val lookalikeSource = """
            package example;
            class Container {}
            class ItemStackHandler {
                ItemStackHandler(int size) {}
            }
            class RecipeWrapper {
                RecipeWrapper(ItemStackHandler handler) {}
            }
            class Lookalike {
                Container value = new RecipeWrapper(new ItemStackHandler(1));
            }
        """.trimIndent()
        lookalike.writeText(lookalikeSource)

        val changes = LegacyRecipeWrapperContainers.migrate(
            directory,
            { "com.modporter.generated.example.compat" },
            dryRun = false
        )

        val migrated = caller.readText()
        assertTrue(changes.isNotEmpty())
        assertTrue(
            migrated.contains(
                "Container output = new com.modporter.generated.example.compat." +
                    "LegacyItemHandlerContainer(new ItemStackHandler(9));"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "Container input = new com.modporter.generated.example.compat." +
                    "LegacyItemHandlerContainer(new ItemStackHandler(size));"
            ),
            migrated
        )
        assertTrue(
            migrated.contains("RecipeWrapper recipeInput = new RecipeWrapper(new ItemStackHandler(1));"),
            migrated
        )
        assertTrue(lookalike.readText() == lookalikeSource)

        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/example/compat/LegacyItemHandlerContainer.java"
        ).readText()
        assertTrue(helper.contains("implements Container"), helper)
        assertTrue(helper.contains("return inventory.getSlots();"), helper)
        assertTrue(helper.contains("return stack.isEmpty() ? ItemStack.EMPTY : stack.split(count);"), helper)
        assertTrue(helper.contains("inventory.setStackInSlot(slot, stack);"), helper)
        assertTrue(helper.contains("return inventory.isItemValid(slot, stack);"), helper)
        assertTrue(helper.contains("public int getMaxStackSize() {\n        return 0;"), helper)
        assertTrue(helper.contains("public boolean stillValid(Player player) {\n        return false;"), helper)

        assertTrue(
            LegacyRecipeWrapperContainers.migrate(
                directory,
                { "com.modporter.generated.example.compat" },
                dryRun = false
            ).isEmpty()
        )
    }

    @Test
    fun `compat package is not requested without a proven container boundary`() {
        val sourceRoot = directory.resolve("src/main/java/example")
        sourceRoot.createDirectories()
        sourceRoot.resolve("InputOnly.java").writeText(
            """
                package example;

                import net.minecraft.world.Container;
                import net.neoforged.neoforge.items.ItemStackHandler;
                import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

                class InputOnly {
                    RecipeWrapper input = new RecipeWrapper(new ItemStackHandler(1));
                }
            """.trimIndent()
        )
        var requested = false

        val changes = LegacyRecipeWrapperContainers.migrate(
            directory,
            {
                requested = true
                "com.modporter.generated.example.compat"
            },
            dryRun = false
        )

        assertTrue(changes.isEmpty())
        assertTrue(!requested)
    }
}
