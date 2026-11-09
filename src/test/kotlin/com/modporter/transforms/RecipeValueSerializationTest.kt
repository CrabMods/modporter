package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateProvenRecipeValueSerialization
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeValueSerializationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `ingredient and fluid callbacks keep their own serializers and unrelated recipe json remains intact`() {
        val source = """
            package example;
            import java.util.List;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.fluids.FluidStack;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            class Writer {
                void write(RegistryFriendlyByteBuf buffer, List<Ingredient> ingredients,
                           List<FluidStack> fluids, List<Step> steps) {
                    ingredients.forEach(i -> i.toJson());
                    ingredients.forEach(i -> i.toNetwork(buffer));
                    fluids.forEach(f -> f.writeToPacket(buffer));
                    steps.forEach(i -> i.toJson());
                    FluidStack.readFromPacket(buffer);
                    String documentation = "i.toJson() f.writeToPacket(buffer)";
                    // i.toJson() f.writeToPacket(buffer)
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val result = pass.migrateProvenRecipeValueSerialization(source)
        assertTrue(result.contains("Ingredient.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, i)"), result)
        assertTrue(result.contains("Ingredient.CONTENTS_STREAM_CODEC.encode((net.minecraft.network.RegistryFriendlyByteBuf) (buffer), i)"), result)
        assertTrue(result.contains("FluidStack.OPTIONAL_STREAM_CODEC.encode((net.minecraft.network.RegistryFriendlyByteBuf) (buffer), f)"), result)
        assertTrue(result.contains("FluidStack.OPTIONAL_STREAM_CODEC.decode((net.minecraft.network.RegistryFriendlyByteBuf) (buffer))"), result)
        assertTrue(result.contains("steps.forEach(i -> i.toJson())"), result)
        assertTrue(result.contains("\"i.toJson() f.writeToPacket(buffer)\""), result)
        assertTrue(result.contains("// i.toJson() f.writeToPacket(buffer)"), result)
        assertEquals(result, pass.migrateProvenRecipeValueSerialization(result))
    }

    @Test
    fun `cross-file ingredient getter authorizes serialization while a same named unrelated getter does not`() {
        val model = directory.resolve("src/main/java/example/Recipe.java")
        model.parent.createDirectories()
        model.writeText("""
            package example;
            import net.minecraft.world.item.crafting.Ingredient;
            class Recipe { Ingredient getIngredient() { return null; } }
        """.trimIndent())
        val source = """
            package example;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            class Writer {
                void write(Recipe recipe, Other other, RegistryFriendlyByteBuf buffer) {
                    recipe.getIngredient().toJson();
                    recipe.getIngredient().toNetwork(buffer);
                    other.getIngredient().toJson();
                }
            }
            class Other { String getIngredient() { return null; } }
        """.trimIndent()
        val writer = model.resolveSibling("Writer.java").also { it.writeText(source) }
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(model, writer))
        val result = pass.migrateProvenRecipeValueSerialization(source)
        assertFalse(result.contains("recipe.getIngredient().toJson()"), result)
        assertFalse(result.contains("recipe.getIngredient().toNetwork(buffer)"), result)
        assertTrue(result.contains("other.getIngredient().toJson()"), result)
    }

    @Test
    fun `unresolved or shadowed ingredient names cannot authorize a json rewrite`() {
        val source = """
            package example;
            import java.util.List;
            import net.minecraft.world.item.crafting.Ingredient;
            class Writer {
                Ingredient value;
                void write(Other value, List<?> unknown) {
                    value.toJson();
                    unknown.forEach(i -> i.toJson());
                }
            }
        """.trimIndent()
        assertEquals(source, StructuralRefactorPass().migrateProvenRecipeValueSerialization(source))
    }
}
