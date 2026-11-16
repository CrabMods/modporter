package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateProvenFluidIngredientAmounts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FluidIngredientAmountsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `declared sized lists and explicit native sized values use the native accessor`() {
        val source = """
            package example;
            import java.util.List;
            import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
            class User {
                List<SizedFluidIngredient> fluids;
                int amount(SizedFluidIngredient sized) {
                    return fluids.get(0).getRequiredAmount() + sized.getRequiredAmount();
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val result = pass.migrateProvenFluidIngredientAmounts(source)
        assertTrue(result.contains("fluids.get(0).amount() + sized.amount()"), result)
        assertEquals(result, pass.migrateProvenFluidIngredientAmounts(result))
    }

    @Test
    fun `var local initialized from a source declared sized list uses the native accessor`() {
        val model = directory.resolve("src/main/java/example/lib/Mixing.java")
        model.parent.createDirectories()
        model.writeText("""
            package example.lib;
            import java.util.List;
            import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
            public class Mixing {
                public List<SizedFluidIngredient> getFluidIngredients() { return null; }
            }
        """.trimIndent())
        val source = """
            package example;
            import example.lib.Mixing;
            class User {
                int amount(Mixing mixing) {
                    var fluids = mixing.getFluidIngredients();
                    return fluids.get(0).getRequiredAmount();
                }
            }
        """.trimIndent()
        val file = directory.resolve("src/main/java/example/User.java")
        file.parent.createDirectories()
        file.writeText(source)
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(model, file))
        val result = pass.migrateProvenFluidIngredientAmounts(source)
        assertTrue(result.contains("fluids.get(0).amount()"), result)
    }

    @Test
    fun `a dependency getter with javap evidence of sized fluid lists uses the native accessor`() {
        val source = """
            package example;
            import com.simibubi.create.content.kinetics.mixer.MixingRecipe;
            class User {
                int amount(MixingRecipe mixing) {
                    var fluids = mixing.getFluidIngredients();
                    return fluids.get(0).getRequiredAmount() + mixing.getFluidIngredients().get(1).getRequiredAmount();
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val result = pass.migrateProvenFluidIngredientAmounts(source)
        assertTrue(
            result.contains("fluids.get(0).amount() + mixing.getFluidIngredients().get(1).amount()"),
            result
        )
        assertEquals(result, pass.migrateProvenFluidIngredientAmounts(result))
    }

    @Test
    fun `library recipe getter without declared type evidence keeps its getter`() {
        val source = """
            package example;
            import other.library.recipe.MixingRecipe;
            class User {
                int amount(MixingRecipe mixing) {
                    var fluids = mixing.getFluidIngredients();
                    return fluids.get(0).getRequiredAmount();
                }
            }
        """.trimIndent()
        assertEquals(source, StructuralRefactorPass().migrateProvenFluidIngredientAmounts(source))
    }

    @Test
    fun `source owned recipe and fluid ingredient classes retain their own getter`() {
        val model = directory.resolve("src/main/java/example/recipe/ProcessingRecipe.java")
        model.parent.createDirectories()
        model.writeText("""
            package example.recipe;
            import java.util.List;
            public class ProcessingRecipe {
                public List<example.FluidIngredient> getFluidIngredients() { return null; }
            }
        """.trimIndent())
        val source = """
            package example;
            import example.recipe.ProcessingRecipe;
            class User {
                int amount(ProcessingRecipe recipe) {
                    var fluids = recipe.getFluidIngredients();
                    return fluids.get(0).getRequiredAmount();
                }
            }
        """.trimIndent()
        val file = directory.resolve("src/main/java/example/User.java")
        file.parent.createDirectories()
        file.writeText(source)
        val ingredient = file.resolveSibling("FluidIngredient.java")
        ingredient.writeText("package example; public class FluidIngredient { public int getRequiredAmount() { return 1; } }")
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(model, file, ingredient))
        assertEquals(source, pass.migrateProvenFluidIngredientAmounts(source))
    }

    @Test
    fun `a project that declares the dependency type itself keeps its own getter`() {
        val model = directory.resolve(
            "src/main/java/com/simibubi/create/content/kinetics/mixer/MixingRecipe.java"
        )
        model.parent.createDirectories()
        model.writeText("""
            package com.simibubi.create.content.kinetics.mixer;
            import java.util.List;
            public class MixingRecipe {
                public List<example.FluidIngredient> getFluidIngredients() { return null; }
            }
        """.trimIndent())
        val source = """
            package example;
            import com.simibubi.create.content.kinetics.mixer.MixingRecipe;
            class User {
                int amount(MixingRecipe recipe) {
                    var fluids = recipe.getFluidIngredients();
                    return fluids.get(0).getRequiredAmount();
                }
            }
        """.trimIndent()
        val file = directory.resolve("src/main/java/example/User.java")
        file.parent.createDirectories()
        file.writeText(source)
        val ingredient = file.resolveSibling("FluidIngredient.java")
        ingredient.writeText("package example; public class FluidIngredient { public int getRequiredAmount() { return 1; } }")
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(model, file, ingredient))
        assertEquals(source, pass.migrateProvenFluidIngredientAmounts(source))
    }

    @Test
    fun `lambda values shadow a proven outer recipe list`() {
        val source = """
            package example;
            import java.util.List;
            import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
            class User {
                List<SizedFluidIngredient> fluids;
                void amount(List<Other> others) {
                    others.forEach(fluids -> fluids.get(0).getRequiredAmount());
                }
            }
            class Other { OtherValue get(int index) { return null; } }
            class OtherValue { int getRequiredAmount() { return 1; } }
        """.trimIndent()
        assertEquals(source, StructuralRefactorPass().migrateProvenFluidIngredientAmounts(source))
    }
}
