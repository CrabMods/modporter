package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** @Override on methods that override nothing on 1.21.1 goes only when the project calls them itself. */
class StaleOverrideAnnotationsTest {

    @TempDir
    lateinit var tempDir: Path

    private val srcDir: Path get() = tempDir.resolve("src/main/java/com/example")

    private fun java(name: String, text: String) {
        srcDir.createDirectories()
        srcDir.resolve("$name.java").writeText(text.trimIndent() + "\n")
    }

    @Test
    fun `a called method that left the platform contract loses its annotation and an uncalled hook keeps it`() {
        java("GrindingRecipe", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

            public abstract class GrindingRecipe implements Recipe<RecipeWrapper> {
                private ResourceLocation id;

                @Override
                public ResourceLocation getId() {
                    return id;
                }

                @Override
                public boolean isLegacyHook(ItemStack stack) {
                    return false;
                }

                @Override
                public String toString() {
                    return "grinding " + getId();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val recipe = srcDir.resolve("GrindingRecipe.java").readText()
        assertTrue(Regex("""\n\s*public ResourceLocation getId\(\)""").containsMatchIn(recipe) && !Regex("""@Override\s+public ResourceLocation getId""").containsMatchIn(recipe), recipe)
        assertTrue(Regex("""@Override\s+public boolean isLegacyHook""").containsMatchIn(recipe), recipe)
        assertTrue(Regex("""@Override\s+public String toString""").containsMatchIn(recipe), recipe)
    }
}
